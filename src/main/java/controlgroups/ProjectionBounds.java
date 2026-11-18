package controlgroups;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import mindustry.Vars;
import mindustry.entities.part.DrawPart;
import mindustry.entities.part.RegionPart;
import mindustry.gen.Crawlc;
import mindustry.gen.Legsc;
import mindustry.gen.Mechc;
import mindustry.gen.Segmentc;
import mindustry.gen.Tankc;
import mindustry.type.UnitType;

/** Rotation-independent envelope of the visible primitives supported by UnitProjection, in world units.
 * The temporary region cache is shared with icon trimming and released after content preparation. */
final class ProjectionBounds {
    private static ObjectMap<TextureRegion, Sprite> packedSprites;
    private final ObjectMap<TextureRegion, Sprite> sprites;
    private double circleRadius = -1;

    ProjectionBounds() {
        sprites = packedSprites == null ? new ObjectMap<>() : packedSprites;
        packedSprites = null;
    }

    /** Modded atlas pixels are disposed immediately after AtlasPackEvent, before content.load() and ClientLoadEvent. */
    static void captureAtlas() {
        ObjectSet<String> names = new ObjectSet<>();
        names.add("circle"); names.add("power-cell");
        for (UnitType type : Vars.content.units()) {
            names.add(type.name);
            names.add("unit-" + type.name); names.add(type.name + "1");
            if (type.fullOverride != null && !type.fullOverride.isEmpty()) names.add(type.fullOverride);
            for (var weapon : type.weapons) {
                if (weapon.name != null && !weapon.name.isEmpty()) names.add(weapon.name);
                for (DrawPart part : weapon.parts) collectNames(part, names, 0);
            }
            for (DrawPart part : type.parts) collectNames(part, names, 0);
        }
        ObjectMap<TextureRegion, Sprite> captured = new ObjectMap<>();
        for (var region : Core.atlas.getRegions()) {
            if (region.name == null) continue;
            for (String name : names) {
                if (region.name.equals(name) || region.name.startsWith(name) && region.name.length() > name.length() && region.name.charAt(name.length()) == '-') {
                    if (UnitProjection.found(region)) captured.put(region, new Sprite(region));
                    break;
                }
            }
        }
        packedSprites = captured;
    }

    private static void collectNames(DrawPart raw, ObjectSet<String> names, int depth) {
        if (!(raw instanceof RegionPart part) || depth >= UnitProjection.MAX_PART_DEPTH) return;
        if (part.name != null && !part.name.isEmpty()) names.add(part.name);
        if (part.children != null) for (int i = 0; i < part.children.size; i++) collectNames(part.children.get(i), names, depth + 1);
    }

    double radius(UnitType type, boolean advanced) {
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
                    for (TextureRegion region : type.treadRegions[i]) if (UnitProjection.found(region)) {
                        for (int side = -1; side <= 1; side += 2)
                            result = max(result, radius(region, -(rect.x + rect.width / 2d) * side / 4,
                                -(rect.y + rect.height / 2d) / 4, 0, rect.width / (4d * region.width), region.scale / 4d));
                    }
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
            double center = Math.hypot(weapon.x, weapon.y), recoil = weapon.recoil;
            for (int pose = 0; pose < 2; pose++) {
                double shift = pose * recoil;
                double angle = weapon.rotate ? 0 : Math.toRadians(weapon.baseRotation);
                double x = weapon.rotate ? 0 : weapon.x + Math.sin(angle) * shift;
                double y = weapon.rotate ? -shift : weapon.y - Math.cos(angle) * shift;
                double spritesRadius = max(weaponSprite(weapon.region, x, y, angle, weapon.flipSprite),
                    weaponSprite(weapon.outlineRegion, x, y, angle, weapon.flipSprite),
                    weaponSprite(weapon.cellRegion, x, y, angle, weapon.flipSprite));
                if (advanced) spritesRadius = max(spritesRadius, weaponSprite(weapon.heatRegion, x, y, angle, weapon.flipSprite));
                result = max(result, spritesRadius + (weapon.rotate ? center : 0));
            }
            if (advanced) {
                for (DrawPart part : weapon.parts) result = max(result, center + Math.abs(recoil) + part(part, 0));
            }
        }
        if (advanced) {
            for (var engine : type.engines)
                result = max(result, Math.hypot(engine.x, engine.y) + Math.abs(engine.radius) * circleRadius());
            for (DrawPart part : type.parts) result = max(result, part(part, 0));
        }
        return Double.isFinite(result) && result >= 0 ? result : 0;
    }

    private double part(DrawPart raw, int depth) {
        if (!(raw instanceof RegionPart part) || depth >= UnitProjection.MAX_PART_DEPTH) return 0;
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

    private double partRegion(TextureRegion region, RegionPart part, double sx, double sy, double pivot) {
        if (!UnitProjection.found(region)) return 0;
        double spriteRadius = radius(region, -part.originX * sx, -part.originY * sy, 0,
            region.scale * sx / 4, region.scale * sy / 4);
        return pivot + spriteRadius;
    }

    private double height(TextureRegion region) {
        return UnitProjection.found(region) ? Math.abs(region.height * (double)region.scale / 4) : 0;
    }

    private double weaponSprite(TextureRegion region, double x, double y, double angle, boolean flip) {
        return UnitProjection.found(region) ? radius(region, x, y, angle,
            region.scale / 4d * (flip ? -1 : 1), region.scale / 4d) : 0;
    }

    private double radius(TextureRegion region) {
        return UnitProjection.found(region) ? sprite(region).radius * Math.abs(region.scale) / 4 : 0;
    }

    private double circleRadius() {
        if (circleRadius < 0) {
            TextureRegion region = Core.atlas == null ? null : Core.atlas.find("circle", (TextureRegion)null);
            circleRadius = UnitProjection.found(region) ? radius(region, 0, 0, 0, 2d / region.width, 2d / region.height) : Math.sqrt(2);
        }
        return circleRadius;
    }

    /** Both bounds contain the visible sprite; their minimum retains translation/rotation correlation where possible. */
    private double radius(TextureRegion region, double x, double y, double angle, double sx, double sy) {
        if (!UnitProjection.found(region)) return 0;
        Sprite sprite = sprite(region);
        if (sprite.empty) return 0;
        double c = Math.cos(angle), s = Math.sin(angle), corners = 0;
        for (int i = 0; i < 4; i++) {
            double px = ((i & 1) == 0 ? sprite.left : sprite.right) * sx;
            double py = ((i & 2) == 0 ? sprite.bottom : sprite.top) * sy;
            corners = Math.max(corners, Math.hypot(x + c * px - s * py, y + s * px + c * py));
        }
        return Math.min(corners, Math.hypot(x, y) + sprite.radius * Math.max(Math.abs(sx), Math.abs(sy)));
    }

    double fallbackRadius(UnitType type) {
        TextureRegion region = UnitProjection.found(type.fullIcon) ? type.fullIcon : type.uiIcon;
        return UnitProjection.found(region) ? sprite(region).radius / Math.max(region.width, region.height) : 0;
    }

    TextureRegion trim(TextureRegion region) {
        if (!UnitProjection.found(region)) return region;
        Sprite sprite = sprite(region);
        if (!sprite.readable || sprite.empty) return region;
        // Font/UI atlas merging can retarget the original region after capture; create UV views only now.
        if (sprite.trimmed == null) sprite.trimmed = new TextureRegion(region, sprite.cropX, sprite.cropY, sprite.cropWidth, sprite.cropHeight);
        return sprite.trimmed;
    }

    private Sprite sprite(TextureRegion region) {
        Sprite known = sprites.get(region);
        if (known != null) return known;
        Sprite result = new Sprite(region);
        sprites.put(region, result);
        return result;
    }

    private static final class Sprite {
        double left, right, bottom, top, radius;
        TextureRegion trimmed;
        boolean empty, readable;
        int cropX, cropY, cropWidth, cropHeight;

        Sprite(TextureRegion region) {
            left = -region.width / 2d; right = -left; bottom = -region.height / 2d; top = -bottom;
            radius = Math.hypot(right, top);
            // Unknown, flipped or mipmapped textures retain the full rectangle as a safe fallback.
            if (Core.atlas == null || region.u > region.u2 || region.v > region.v2) return;
            try {
                var filter = region.texture.getMinFilter();
                if (filter == null || filter.isMipMap()) return;
                var pixels = Core.atlas.getPixmap(region);
                if (pixels.width != region.width || pixels.height != region.height || pixels.pixmap.isDisposed()) return;
                int minX = region.width, minY = region.height, maxX = -1, maxY = -1;
                double squared = 0;
                for (int y = 0; y < pixels.height; y++) for (int x = 0; x < pixels.width; x++) {
                    if ((pixels.get(x, y) & 255) == 0) continue;
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                    // Half a texel beyond the pixel's cell includes bilinear filtering support, capped by the draw quad.
                    double px = Math.max(Math.abs(Math.max(0, x - .5) - region.width / 2d),
                        Math.abs(Math.min(region.width, x + 1.5) - region.width / 2d));
                    double py = Math.max(Math.abs(Math.max(0, y - .5) - region.height / 2d),
                        Math.abs(Math.min(region.height, y + 1.5) - region.height / 2d));
                    squared = Math.max(squared, px * px + py * py);
                }
                empty = maxX < minX;
                readable = true;
                if (empty) { radius = 0; return; }
                left = Math.max(0, minX - .5) - region.width / 2d;
                right = Math.min(region.width, maxX + 1.5) - region.width / 2d;
                bottom = region.height / 2d - Math.min(region.height, maxY + 1.5);
                top = region.height / 2d - Math.max(0, minY - .5);
                radius = Math.sqrt(squared);
                cropX = minX; cropY = minY; cropWidth = maxX - minX + 1; cropHeight = maxY - minY + 1;
            } catch (RuntimeException ignored) {
                // Standalone/non-atlas content need not expose pixels. Keep its original UVs and full bounds.
            }
        }
    }

    private static double max(double a, double b) {
        return Math.max(Double.isFinite(a) && a >= 0 ? a : 0, Double.isFinite(b) && b >= 0 ? b : 0);
    }
    private static double max(double a, double b, double c) { return max(a, max(b, c)); }
}
