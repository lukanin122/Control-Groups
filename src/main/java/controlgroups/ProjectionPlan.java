package controlgroups;

import arc.graphics.g2d.TextureRegion;
import mindustry.entities.part.DrawPart;
import mindustry.entities.part.RegionPart;
import mindustry.type.UnitType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Content-time ordering of the supported v160.7 draw operations. No world objects or frame data. */
final class ProjectionPlan {
    enum Kind { LOCOMOTION, OUTLINE, ENGINE, BODY, CELL, WEAPON_OUTLINE, WEAPON, WEAPON_CELL, WEAPON_HEAT,
        PART_OUTLINE, PART, PART_HEAT }

    static final class Operation {
        final Kind kind;
        final int index, side;
        final TextureRegion region;
        final float groundZ, flyingZ;
        final boolean advanced, outlineTint, part;

        Operation(Kind kind, int index, int side, TextureRegion region, float groundZ, float flyingZ,
                  boolean advanced, boolean outlineTint) {
            this.kind = kind; this.index = index; this.side = side; this.region = region;
            this.groundZ = groundZ; this.flyingZ = flyingZ;
            this.advanced = advanced; this.outlineTint = outlineTint;
            part = kind == Kind.PART_OUTLINE || kind == Kind.PART || kind == Kind.PART_HEAT;
        }
    }

    static final class Part {
        final RegionPart region;
        final int parent, parentSide, mount, sideOverride, sides;
        final boolean weapon;

        Part(RegionPart region, int parent, int parentSide, int mount, int sideOverride, boolean weapon) {
            this.region = region; this.parent = parent; this.parentSide = parentSide; this.mount = mount;
            this.sideOverride = sideOverride; this.weapon = weapon;
            sides = region.mirror && sideOverride == -1 ? 2 : 1;
        }
    }

    final Operation[] ground, flying, basicGround, basicFlying;
    final Part[] parts;
    final int mounts;

    ProjectionPlan(UnitType type) {
        Builder builder = new Builder();
        mounts = type.weapons.size;
        float g = type.groundLayer + Math.max(0, Math.min(.01f, type.hitSize / 4000f)), f = type.flyingLayer;
        if (!Float.isFinite(g)) g = type.groundLayer;
        builder.add(Kind.LOCOMOTION, 0, 0, null, g - .02f, f - .02f, false, false);
        if (type.drawBody) builder.add(Kind.OUTLINE, 0, 0, type.outlineRegion, g, f, false, true);
        for (int i = 0; i < mounts; i++) {
            var weapon = type.weapons.get(i);
            if (!weapon.top) builder.add(Kind.WEAPON_OUTLINE, i, 0, weapon.outlineRegion,
                g + weapon.layerOffset, f + weapon.layerOffset, false, true);
        }
        if (!type.engines.isEmpty()) builder.add(Kind.ENGINE, 0, 0, null, type.engineLayer > 0 ? type.engineLayer : g,
            type.engineLayer > 0 ? type.engineLayer : f, true, false);
        if (type.drawBody) builder.add(Kind.BODY, 0, 0, type.region, g, f, false, false);
        if (type.drawCell) builder.add(Kind.CELL, 0, 0, type.cellRegion, g, f, false, false);
        for (int i = 0; i < mounts; i++) {
            var weapon = type.weapons.get(i);
            float wg = g + weapon.layerOffset, wf = f + weapon.layerOffset;
            if (weapon.top) builder.add(Kind.WEAPON_OUTLINE, i, 0, weapon.outlineRegion, wg, wf, false, false);
            for (var part : weapon.parts) if (part.under) builder.part(part, -1, 0, i, -1, true, 0, wg, wf);
            builder.add(Kind.WEAPON, i, 0, weapon.region, wg, wf, false, false);
            builder.add(Kind.WEAPON_CELL, i, 0, weapon.cellRegion, wg, wf, false, false);
            builder.add(Kind.WEAPON_HEAT, i, 0, weapon.heatRegion, wg, wf, true, false);
            for (var part : weapon.parts) if (!part.under) builder.part(part, -1, 0, i, -1, true, 0, wg, wf);
        }
        // UnitType does not split its own parts into under/above passes.
        for (var part : type.parts) builder.part(part, -1, 0, part.weaponIndex, -1, false, 0, g, f);
        parts = builder.parts.toArray(new Part[0]);
        Operation[] insertion = builder.operations.toArray(new Operation[0]);
        ground = sorted(insertion, false, false); flying = sorted(insertion, true, false);
        basicGround = sorted(insertion, false, true); basicFlying = sorted(insertion, true, true);
    }

    private static Operation[] sorted(Operation[] insertion, boolean flying, boolean basic) {
        Operation[] result = basic ? Arrays.stream(insertion).filter(op -> !op.advanced).toArray(Operation[]::new) : insertion.clone();
        // Stable ties preserve the native submission order, including mirrored parts and children.
        Arrays.sort(result, Comparator.comparingInt(op -> layerKey(flying ? op.flyingZ : op.groundZ)));
        return result;
    }

    static int layerKey(float z) {
        // SpriteBatch sorts this biased float key; nearby Z values may become equal after rounding.
        return Float.floatToRawIntBits(z + 16f);
    }

    private static final class Builder {
        final ArrayList<Part> parts = new ArrayList<>();
        final ArrayList<Operation> operations = new ArrayList<>();

        void add(Kind kind, int index, int side, TextureRegion region, float g, float f, boolean advanced, boolean outlineTint) {
            if (region != null && !UnitProjection.found(region)) return;
            if (region == null && kind != Kind.LOCOMOTION && kind != Kind.ENGINE) return;
            operations.add(new Operation(kind, index, side, region, g, f, advanced, outlineTint));
        }

        void part(DrawPart raw, int parent, int parentSide, int mount, int sideOverride, boolean weapon, int depth, float g, float f) {
            if (!(raw instanceof RegionPart region) || depth >= UnitProjection.MAX_PART_DEPTH) return;
            int index = parts.size();
            Part spec = new Part(region, parent, parentSide, mount, sideOverride, weapon);
            parts.add(spec);
            float pg = region.layer > 0 ? region.layer : g, pf = region.layer > 0 ? region.layer : f;
            if (region.under && region.turretShading) { pg = g - .0001f; pf = f - .0001f; }
            pg += region.layerOffset; pf += region.layerOffset;
            for (int side = 0; side < spec.sides; side++) {
                int r = sideOverride == -1 ? side : sideOverride;
                if (region.outline && region.drawRegion && region.outlines.length > 0)
                    add(Kind.PART_OUTLINE, index, side, region.outlines[Math.min(r, region.outlines.length - 1)],
                        pg + region.outlineLayerOffset, pf + region.outlineLayerOffset, true, false);
                if (region.drawRegion && region.regions.length > 0)
                    add(Kind.PART, index, side, region.regions[Math.min(r, region.regions.length - 1)], pg, pf, true, false);
                add(Kind.PART_HEAT, index, side, region.heat,
                    region.turretShading ? region.turretHeatLayer : pg + region.heatLayerOffset,
                    region.turretShading ? region.turretHeatLayer : pf + region.heatLayerOffset, true, false);
            }
            // RegionPart restores its incoming Z before traversing children, after both mirror sides.
            for (int side = 0; side < spec.sides; side++) for (var child : region.children)
                part(child, index, side, mount, sideOverride == -1 ? side : sideOverride, weapon, depth + 1, g, f);
        }
    }
}
