package controlgroups;

import arc.graphics.g2d.TextureRegion;
import mindustry.entities.part.DrawPart;
import mindustry.entities.part.RegionPart;
import mindustry.gen.Crawlc;
import mindustry.gen.Legsc;
import mindustry.gen.Mechc;
import mindustry.gen.Segmentc;
import mindustry.gen.Tankc;
import mindustry.type.UnitType;

/** Rotation-independent envelope of the primitives supported by UnitProjection, in world units.
 * Prepared once from content, never from the current representative or its animation. */
final class ProjectionBounds {
    private ProjectionBounds() { }

    static double radius(UnitType type, boolean advanced) {
        // Keep hitSize-dependent strokes/fallback out of metadata: invalid hitSize uses the current B in HudLayout.
        double result = 0;
        if (type.sample instanceof Crawlc || type.sample instanceof Segmentc || !UnitProjection.found(type.region))
            return max(result, radius(UnitProjection.found(type.fullIcon) ? type.fullIcon : type.uiIcon));
        if (type.drawBody) result = max(result, radius(type.region), radius(type.outlineRegion));
        if (type.drawCell) result = max(result, radius(type.cellRegion));
        if (type.sample instanceof Tankc) {
            result = max(result, radius(type.treadRegion));
            if (type.treadRegions != null && type.treadRects != null) {
                for (int i = 0; i < Math.min(type.treadRegions.length, type.treadRects.length); i++) {
                    var rect = type.treadRects[i];
                    if (rect == null || type.treadRegions[i] == null) continue;
                    double center = Math.hypot(rect.x + rect.width / 2d, rect.y + rect.height / 2d) / 4;
                    for (TextureRegion region : type.treadRegions[i]) if (UnitProjection.found(region))
                        result = max(result, center + Math.hypot(rect.width, region.height * (double)region.scale) / 8);
                }
            }
        }
        if (type.sample instanceof Mechc) {
            // walkExtend(false) is in [-mechStride, mechStride]; elevation interpolates toward (±2, 2).
            result = max(result, radius(type.baseRegion),
                Math.max(Math.abs(type.mechStride), Math.sqrt(8)) + radius(type.legRegion));
        }
        if (type.sample instanceof Legsc) {
            double hip = Math.abs(type.legBaseOffset);
            if (type.legStraightness > 0) hip *= Math.abs(1 - type.baseLegStraightness) +
                Math.abs(type.baseLegStraightness) * Math.hypot(1, type.legStraightLength);
            double reach = Math.abs(type.legLength * (double)type.legMaxLength);
            result = max(result, hip + reach / 2 + radius(type.jointRegion), hip + reach + radius(type.footRegion));
            result = max(result, hip + reach + max(height(type.legRegion), height(type.legBaseRegion)) / 2);
        }
        for (var weapon : type.weapons) {
            double center = Math.hypot(weapon.x, weapon.y) + Math.abs(weapon.recoil);
            double weaponRadius = max(radius(weapon.region), radius(weapon.outlineRegion), radius(weapon.cellRegion));
            if (advanced) {
                weaponRadius = max(weaponRadius, radius(weapon.heatRegion));
                for (DrawPart part : weapon.parts) weaponRadius = max(weaponRadius, part(part, 0));
            }
            result = max(result, center + weaponRadius);
        }
        if (advanced) {
            for (var engine : type.engines)
                result = max(result, Math.hypot(engine.x, engine.y) + Math.abs(engine.radius) * Math.sqrt(2));
            for (DrawPart part : type.parts) result = max(result, part(part, 0));
        }
        return Double.isFinite(result) && result >= 0 ? result : 0;
    }

    private static double part(DrawPart raw, int depth) {
        if (raw == null || raw.getClass() != RegionPart.class || depth >= UnitProjection.MAX_PART_DEPTH) return 0;
        RegionPart part = (RegionPart)raw;
        // A finite static envelope covers progress in [0, 1]. Unclamped custom callbacks may be unbounded;
        // preserve their existing rendering rather than silently clamp their animation or user's resize.
        if (part.moves == null || part.children == null) return 0;
        double sx = extent(part.xScl, part.growX, part, true, true);
        double sy = extent(part.yScl, part.growY, part, false, true);
        double tx = extent(part.x, part.moveX, part, true, false);
        double ty = extent(part.y, part.moveY, part, false, false);
        // Draw.rect rotates around (originX, originY), not around the region's centre.
        double pivot = Math.hypot(tx + Math.abs(part.originX) * sx, ty + Math.abs(part.originY) * sy);
        double result = 0;
        if (part.drawRegion) {
            if (part.regions != null) for (TextureRegion region : part.regions)
                result = max(result, partRegion(region, part, sx, sy, pivot));
            if (part.outline && part.outlines != null) for (TextureRegion region : part.outlines)
                result = max(result, partRegion(region, part, sx, sy, pivot));
        }
        result = max(result, partRegion(part.heat, part, sx, sy, pivot));
        // Children inherit the moved anchor and rotation, but neither the parent's growth nor its draw origin.
        double translation = Math.hypot(tx, ty);
        for (DrawPart child : part.children) result = max(result, translation + part(child, depth + 1));
        return Double.isFinite(result) && result >= 0 ? result : 0;
    }

    private static double extent(double base, double movement, RegionPart part, boolean x, boolean grow) {
        double low = base + Math.min(0, movement), high = base + Math.max(0, movement);
        for (var move : part.moves) {
            double amount = grow ? (x ? move.gx : move.gy) : (x ? move.x : move.y);
            low += Math.min(0, amount); high += Math.max(0, amount);
        }
        return Math.max(Math.abs(low), Math.abs(high));
    }

    private static double partRegion(TextureRegion region, RegionPart part, double sx, double sy, double pivot) {
        if (!UnitProjection.found(region)) return 0;
        return pivot + Math.hypot((width(region) / 2 + Math.abs(part.originX)) * sx,
            (height(region) / 2 + Math.abs(part.originY)) * sy);
    }

    private static double width(TextureRegion region) {
        return UnitProjection.found(region) ? Math.abs(region.width * (double)region.scale / 4) : 0;
    }

    private static double height(TextureRegion region) {
        return UnitProjection.found(region) ? Math.abs(region.height * (double)region.scale / 4) : 0;
    }

    private static double radius(TextureRegion region) { return Math.hypot(width(region), height(region)) / 2; }
    private static double max(double a, double b) {
        return Math.max(Double.isFinite(a) && a >= 0 ? a : 0, Double.isFinite(b) && b >= 0 ? b : 0);
    }
    private static double max(double a, double b, double c) { return max(a, max(b, c)); }
}
