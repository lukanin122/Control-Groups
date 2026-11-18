package controlgroups;

import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.Scaled;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.units.WeaponMount;
import mindustry.entities.part.DrawPart.PartParams;
import mindustry.entities.part.RegionPart;
import mindustry.gen.Crawlc;
import mindustry.gen.Legsc;
import mindustry.gen.Mechc;
import mindustry.gen.Segmentc;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.gen.UnderwaterMovec;
import mindustry.type.UnitType;

/** Read-only sprite renderer. Never calls UnitType/Weapon/Ability virtual drawing code. */
public final class UnitProjection {
    public static final float DRIFT_FRACTION = .07f;
    static final int MAX_PART_DEPTH = 16;
    static final float BURNING_RADIUS = .45f, SHIELD_RADIUS = .65f, STATUS_STROKE_RADIUS = .6f;
    private static final Color FIRE = Color.valueOf("ff795e");
    private final IntSet failed = new IntSet();
    private final Vec2 offset = new Vec2();
    private final Color partColor = new Color(), partMix = new Color();
    private final UnitProfiles profiles;
    private final MountState[] mounts;
    private final PartState[] parts;
    private float unitColor, unitMix, outlineColor, cellColor;

    private static final class MountState {
        float x, y, rotation;
    }

    private static final class SideState {
        float x, y, anchorX, anchorY, rotation, sx, sy;
        float outlineColor, outlineMix, color, mix, heatColor, heatMix;
    }

    private static final class PartState {
        final PartParams params = new PartParams();
        final SideState[] sides = {new SideState(), new SideState()};
        float motionRotation;
        boolean valid;
    }
    private float originX, originY, scale, unitX, unitY, alpha, referenceHit;
    private final float[] driftX = new float[11], driftY = new float[11];

    public UnitProjection(UnitProfiles profiles) {
        this.profiles = profiles;
        int mountCount = 0, partCount = 0;
        for (var profile : profiles.all) {
            mountCount = Math.max(mountCount, profile.projectionPlan.mounts);
            partCount = Math.max(partCount, profile.projectionPlan.parts.length);
        }
        mounts = new MountState[mountCount]; parts = new PartState[partCount];
        for (int i = 0; i < mounts.length; i++) mounts[i] = new MountState();
        for (int i = 0; i < parts.length; i++) parts[i] = new PartState();
    }

    public void clear() {
        java.util.Arrays.fill(driftX, 0); java.util.Arrays.fill(driftY, 0);
        failed.clear();
    }

    public void draw(Unit unit, int group, float x, float y, float pixels, float zone, float referenceHit,
                     float delta, boolean advanced, float alpha) {
        if (unit == null || !unit.isValid() || !Float.isFinite(pixels) || pixels <= 0 ||
            !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(unit.x) || !Float.isFinite(unit.y) ||
            !Float.isFinite(unit.rotation)) return;
        this.referenceHit = HudLayout.referenceHit(referenceHit, 8);
        scale = pixels / this.referenceHit;
        if (!Float.isFinite(scale) || scale <= 0) return;
        float color = Draw.getColorPacked(), mix = Draw.getMixColorPacked(), z = Draw.z();
        float xs = Draw.xscl, ys = Draw.yscl, stroke = Lines.getStroke();
        Blending blend = Draw.getBlend();
        this.alpha = ModSettings.finite(alpha, 1, 0, 1);
        float maxDrift = Float.isFinite(zone) ? Math.max(0, zone) * DRIFT_FRACTION : 0;
        float vx = ModSettings.finite(unit.vel.x, 0, -1, 1) * maxDrift;
        float vy = ModSettings.finite(unit.vel.y, 0, -1, 1) * maxDrift;
        float interpolation = 1f - (float)Math.exp(-12f * Math.max(0, Float.isFinite(delta) ? delta : 0));
        driftX[group] = Mathf.lerp(Mathf.clamp(driftX[group], -maxDrift, maxDrift), vx, interpolation);
        driftY[group] = Mathf.lerp(Mathf.clamp(driftY[group], -maxDrift, maxDrift), vy, interpolation);
        originX = x + driftX[group]; originY = y + driftY[group];
        unitX = unit.x; unitY = unit.y;
        try {
            Draw.scl(1f);
            if (failed.contains(unit.type.id)) {
                fallback(unit, pixels);
            } else {
                render(unit, advanced);
            }
        } catch (RuntimeException exception) {
            if (failed.add(unit.type.id)) Log.warn("[Control Groups] Projection fallback for @: @", unit.type.name, exception.toString());
            Draw.reset(); Draw.scl(1f); blend(Blending.normal);
            fallback(unit, pixels);
        } finally {
            blend(blend); Draw.color(color); Draw.mixcol(mix);
            Draw.scl(xs, ys); Draw.z(z); Lines.stroke(stroke);
        }
    }

    private void render(Unit unit, boolean advanced) {
        UnitType type = unit.type;
        // Packed multi-segment bodies are a safe recognisable fallback for special geometries.
        if (unit instanceof Crawlc || unit instanceof Segmentc || !found(type.region)) {
            blend(Blending.normal); tint(unit);
            sprite(found(type.fullIcon) ? type.fullIcon : type.uiIcon, unit.x, unit.y, unit.rotation - 90, false);
            return;
        }
        var profile = profiles.get(type);
        if (profile == null) { fallback(unit, referenceHit * scale); return; }
        ProjectionPlan plan = profile.projectionPlan;
        prepare(unit, plan, advanced);
        boolean flying = unit.elevation > .5f || type.flying && unit.dead;
        var operations = advanced ? (flying ? plan.flying : plan.ground) : (flying ? plan.basicFlying : plan.basicGround);
        // The UI batch need not sort: commands already have native Z order, and retain the caller's UI Z.
        for (var op : operations) {
            if (!op.part) {
                if (op.kind == ProjectionPlan.Kind.WEAPON_HEAT && unit.mounts[op.index].heat <= 0) continue;
                blend(op.kind == ProjectionPlan.Kind.WEAPON_HEAT ? Blending.additive : Blending.normal);
                Draw.color(unitColor); Draw.mixcol(unitMix);
            }
            switch (op.kind) {
                case LOCOMOTION -> locomotion(unit);
                case ENGINE -> engines(unit);
                case OUTLINE -> {
                    Draw.color(outlineColor); sprite(op.region, unit.x, unit.y, unit.rotation - 90, false);
                }
                case BODY -> {
                    if (unit instanceof UnderwaterMovec) {
                        partMix.set(unit.floorOn().mapColor).mul(.9f); Draw.mixcol(partMix, 1);
                    }
                    sprite(op.region, unit.x, unit.y, unit.rotation - 90, false);
                }
                case CELL -> {
                    Draw.color(cellColor); sprite(op.region, unit.x, unit.y, unit.rotation - 90, false);
                }
                case WEAPON_OUTLINE, WEAPON, WEAPON_CELL, WEAPON_HEAT -> {
                    WeaponMount mount = unit.mounts[op.index]; MountState pose = mounts[op.index];
                    if (op.kind == ProjectionPlan.Kind.WEAPON_HEAT) {
                        Draw.color(mount.weapon.heatColor, alpha * finite(mount.heat));
                    } else if (op.kind == ProjectionPlan.Kind.WEAPON_CELL) Draw.color(cellColor);
                    else if (op.outlineTint) Draw.color(outlineColor);
                    sprite(op.region, pose.x, pose.y, pose.rotation, mount.weapon.flipSprite);
                }
                case PART_OUTLINE, PART, PART_HEAT -> {
                    PartState frame = parts[op.index];
                    if (!frame.valid) continue;
                    SideState side = frame.sides[op.side];
                    if (op.kind == ProjectionPlan.Kind.PART_OUTLINE) {
                        blend(Blending.normal); Draw.color(side.outlineColor); Draw.mixcol(side.outlineMix);
                    } else if (op.kind == ProjectionPlan.Kind.PART_HEAT) {
                        blend(Blending.additive); Draw.color(side.heatColor); Draw.mixcol(side.heatMix);
                    } else {
                        blend(plan.parts[op.index].region.blending); Draw.color(side.color); Draw.mixcol(side.mix);
                    }
                    partRegion(op.region, side.x, side.y, side.rotation, side.sx, side.sy, plan.parts[op.index].region);
                }
            }
        }
        Draw.mixcol();
        if (unit.hasEffect(StatusEffects.burning) || unit.hasEffect(StatusEffects.melting)) {
            blend(Blending.normal);
            Draw.color(FIRE, alpha * (0.3f + Mathf.absin(Time.time, 3f, 0.4f)));
            Lines.stroke(Math.max(1f, scale));
            float radius = referenceHit * scale * BURNING_RADIUS;
            if (safeQuad(originX, originY, radius * 2, radius * 2)) {
                // Arc's circleVertices grows without a bound; do not let deliberate extreme resize create millions of segments.
                Lines.poly(originX, originY, Math.max(12, Math.min(256, Lines.circleVertices(radius))), radius);
            }
        }
        if (advanced && unit.shield > 0 && type.drawShields) {
            blend(Blending.normal);
            Draw.color(unit.team.color, alpha * 0.6f);
            Lines.stroke(Math.max(1f, scale));
            float radius = referenceHit * scale * SHIELD_RADIUS;
            if (safeQuad(originX, originY, radius * 2, radius * 2))
                Lines.poly(originX, originY, 6, radius, Time.time * 0.2f);
        }
    }

    private void locomotion(Unit unit) {
        UnitType type = unit.type;
        Draw.color(unitColor); Draw.mixcol(unitMix);
        if (unit instanceof Tankc tank) {
            sprite(type.treadRegion, unit.x, unit.y, unit.rotation - 90, false);
            if (type.treadRegions != null && type.treadFrames > 0) {
                int frame = Math.floorMod((int)tank.treadTime(), type.treadFrames);
                for (int i = 0; i < type.treadRects.length; i++) {
                    var rect = type.treadRects[i];
                    for (int side = -1; side <= 1; side += 2) {
                        offset.set(-(rect.x + rect.width / 2f) * side, -(rect.y + rect.height / 2f)).rotate(unit.rotation - 90).scl(0.25f);
                        TextureRegion region = type.treadRegions[i][frame];
                        region(region, unit.x + offset.x, unit.y + offset.y,
                            rect.width / 4f, region.height * region.scale / 4f, unit.rotation - 90, false);
                    }
                }
            }
        }
        if (unit instanceof Mechc mech) {
            var floor = unit.isFlying() ? null : unit.floorOn();
            partColor.set(Color.white);
            if (floor != null && floor.isLiquid) partColor.lerp(floor.mapColor, .5f);
            float legColor = packed(partColor, alpha);
            float sin = Mathf.lerp(Mathf.sin(mech.walkExtend(true), 2f / Mathf.PI, 1), 0, unit.elevation);
            float extension = Mathf.lerp(mech.walkExtend(false), 0, unit.elevation);
            float boost = unit.elevation * 2;
            for (int side = -1; side <= 1; side += 2) {
                partMix.set(type.mechLegColor).lerp(Color.white, Mathf.clamp(unit.hitTime));
                float step = type.mechStride != 0 ? Math.max(0, side * extension / type.mechStride) : 0;
                Draw.color(legColor); Draw.mixcol(partMix, Math.max(step, finite(unit.hitTime)));
                if (found(type.legRegion)) region(type.legRegion,
                    unit.x + Angles.trnsx(mech.baseRotation(), extension * side - boost, -boost * side),
                    unit.y + Angles.trnsy(mech.baseRotation(), extension * side - boost, -boost * side),
                    type.legRegion.width * type.legRegion.scale / 4f * side,
                    type.legRegion.height * type.legRegion.scale / 4f * (1 - Math.max(-sin * side, 0) * 0.5f),
                    mech.baseRotation() - 90 + 35 * side * unit.elevation, false);
            }
            // v160.7's base drown Draw.color uses Tmp.c1 as both interpolation scratch and target: white.
            Draw.color(unitColor); Draw.mixcol(Color.white, finite(unit.hitTime));
            sprite(type.baseRegion, unit.x, unit.y, mech.baseRotation() - 90, false);
        }
        Draw.color(unitColor); Draw.mixcol(unitMix);
        if (unit instanceof Legsc legs && legs.legs() != null) {
            for (int i = legs.legs().length - 1; i >= 0; i--) {
                var leg = legs.legs()[i];
                // legOffset only writes the supplied vector; unit and leg positions are left intact.
                legs.legOffset(offset, i).add(unit);
                legLine(type.legRegion, offset.x, offset.y, leg.joint.x, leg.joint.y);
                legLine(type.legBaseRegion, leg.joint.x, leg.joint.y, leg.base.x, leg.base.y);
                sprite(type.jointRegion, leg.joint.x, leg.joint.y, 0, false);
                sprite(type.footRegion, leg.base.x, leg.base.y, Angles.angle(offset.x, offset.y, leg.base.x, leg.base.y), false);
            }
        }
    }

    private void engines(Unit unit) {
        UnitType type = unit.type;
        if (!(unit.elevation > .01f || !type.useEngineElevation)) return;
        Draw.mixcol();
        for (var engine : type.engines) {
            offset.set(engine.x, engine.y).rotate(unit.rotation - 90);
            float radius = engine.radius * (.8f + Mathf.absin(Time.time, 2f, .2f)) * scale * (type.useEngineElevation ? unit.elevation : 1f);
            float ex = px(unit.x + offset.x), ey = py(unit.y + offset.y);
            if (!safeQuad(ex, ey, radius * 2, radius * 2) || radius <= 0) continue;
            Color outer = engine.color != null ? engine.color : type.engineColor == null ? unit.team.color : type.engineColor;
            Draw.color(outer, alpha * outer.a); Fill.circle(ex, ey, radius);
            Draw.color(type.engineColorInner, alpha * type.engineColorInner.a); Fill.circle(ex, ey, radius * .45f);
        }
    }

    /** Evaluate poses/progress once, before the prepared operations consume them in Z order. */
    private void prepare(Unit unit, ProjectionPlan plan, boolean advanced) {
        tint(unit); unitColor = Draw.getColorPacked(); unitMix = Draw.getMixColorPacked();
        // Arc Draw.color overwrites Tmp.c1 before lerping to it in applyOutlineColor (v160.7).
        // Match the actual white outline tint, retaining the independent drowning mix.
        outlineColor = unitColor;
        cellColor(unit); cellColor = Draw.getColorPacked();
        if (unit.mounts.length != plan.mounts) throw new IllegalArgumentException("Changed weapon topology");
        for (int i = 0; i < plan.mounts; i++) {
            WeaponMount mount = unit.mounts[i];
            if (mount.weapon != unit.type.weapons.get(i)) throw new IllegalArgumentException("Changed weapon topology");
            var weapon = mount.weapon; MountState pose = mounts[i];
            float rotation = unit.rotation - 90;
            pose.rotation = rotation + (weapon.rotate ? mount.rotation : weapon.baseRotation);
            float recoil = Mathf.pow(mount.recoil, weapon.recoilPow) * weapon.recoil;
            pose.x = unit.x + Angles.trnsx(rotation, weapon.x, weapon.y) + Angles.trnsx(pose.rotation, 0, -recoil);
            pose.y = unit.y + Angles.trnsy(rotation, weapon.x, weapon.y) + Angles.trnsy(pose.rotation, 0, -recoil);
        }
        if (advanced) for (int i = 0; i < plan.parts.length; i++) preparePart(unit, plan, i);
    }

    private void preparePart(Unit unit, ProjectionPlan plan, int index) {
        ProjectionPlan.Part spec = plan.parts[index]; RegionPart part = spec.region;
        PartState frame = parts[index]; PartParams params = frame.params;
        frame.valid = false;
        if (spec.parent >= 0) {
            PartState parent = parts[spec.parent];
            if (!parent.valid) return;
            PartParams p = parent.params; SideState side = parent.sides[spec.parentSide];
            int r = plan.parts[spec.parent].sideOverride == -1 ? spec.parentSide : plan.parts[spec.parent].sideOverride;
            float sign = (r == 0 ? 1 : -1) * p.sideMultiplier;
            params.set(p.warmup, p.reload, p.smoothReload, p.heat, p.recoil, p.charge,
                side.anchorX, side.anchorY, parent.motionRotation * sign + p.rotation);
            params.sideMultiplier = p.sideMultiplier; params.sideOverride = r; params.life = p.life;
        } else {
            WeaponMount mount = spec.mount >= 0 && spec.mount < unit.mounts.length ? unit.mounts[spec.mount] : null;
            MountState pose = spec.weapon ? mounts[spec.mount] : null;
            float x = spec.weapon ? pose.x : unit.x, y = spec.weapon ? pose.y : unit.y;
            float rotation = spec.weapon ? pose.rotation + 90 : unit.rotation;
            if (mount == null) params.set(0, 0, 0, 0, 0, 0, x, y, rotation);
            else params.set(mount.warmup, mount.weapon.reload > 0 ? mount.reload / mount.weapon.reload : 0,
                mount.smoothReload, mount.heat, mount.recoil, mount.charge, x, y, rotation);
            if (spec.weapon) {
                params.sideMultiplier = mount.weapon.flipSprite ? -1 : 1;
                if (part.recoilIndex >= 0 && mount.recoils != null && part.recoilIndex < mount.recoils.length)
                    params.recoil = mount.recoils[part.recoilIndex];
            } else if (unit instanceof Scaled scaled) params.life = scaled.fin();
        }
        float progress = part.progress.getClamp(params, part.clampProgress);
        float grow = part.growProgress.getClamp(params, part.clampProgress);
        float heat = found(part.heat) ? part.heatProgress.getClamp(params, part.clampProgress) : 0;
        if (!Float.isFinite(progress) || !Float.isFinite(grow) || !Float.isFinite(heat)) return;
        float mx = part.moveX * progress, my = part.moveY * progress, mr = part.moveRot * progress + part.rotation;
        float gx = part.growX * grow, gy = part.growY * grow;
        for (int i = 0; i < part.moves.size; i++) {
            var move = part.moves.get(i); float p = move.progress.getClamp(params, part.clampProgress);
            mx += move.x * p; my += move.y * p; mr += move.rot * p; gx += move.gx * p; gy += move.gy * p;
        }
        frame.motionRotation = mr;
        // Native RegionPart keeps color/mix between mirror sides; children receive the original incoming state.
        float color = unitColor, mix = unitMix;
        for (int side = 0; side < spec.sides; side++) {
            int r = spec.sideOverride == -1 ? side : spec.sideOverride;
            float sign = (r == 0 ? 1 : -1) * params.sideMultiplier;
            SideState pose = frame.sides[side];
            pose.anchorX = params.x + Angles.trnsx(params.rotation - 90, (part.x + mx) * sign, part.y + my);
            pose.anchorY = params.y + Angles.trnsy(params.rotation - 90, (part.x + mx) * sign, part.y + my);
            pose.x = pose.anchorX; pose.y = pose.anchorY; pose.rotation = mr * sign + params.rotation - 90;
            pose.sx = (part.xScl + gx) * sign; pose.sy = part.yScl + gy;
            if (part.originX != 0 || part.originY != 0) {
                pose.x -= Angles.trnsx(params.rotation - 90, -part.originX * pose.sx, -part.originY * pose.sy) + part.originX * pose.sx;
                pose.y -= Angles.trnsy(params.rotation - 90, -part.originX * pose.sx, -part.originY * pose.sy) + part.originY * pose.sy;
            }
            pose.outlineColor = color; pose.outlineMix = mix;
            boolean base = part.drawRegion && part.regions.length > 0 && found(part.regions[Math.min(r, part.regions.length - 1)]);
            if (base) {
                if (part.color != null) {
                    partColor.set(part.color); if (part.colorTo != null) partColor.lerp(part.colorTo, progress);
                    color = packed(partColor, alpha);
                }
                if (part.mixColor != null) {
                    partMix.set(part.mixColor); if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress);
                    mix = packed(partMix, 1);
                }
            }
            pose.color = color; pose.mix = mix;
            if (base && part.color != null) color = unitColor;
            pose.heatColor = packed(part.heatColor, alpha * heat); pose.heatMix = mix;
            if (found(part.heat)) color = unitColor;
        }
        frame.valid = true;
    }

    private static float packed(Color color, float opacity) {
        return Color.toFloatBits(finite(color.r), finite(color.g), finite(color.b), Mathf.clamp(finite(color.a * opacity)));
    }

    private static float finite(float value) { return Float.isFinite(value) ? value : 0; }

    private static void blend(Blending desired) {
        // SpriteBatch.setBlending only assigns a field in this Arc version. Its unsorted UI path
        // applies that field at flush, so flush BEFORE changing it, once per contiguous blend run.
        if (Draw.getBlend() != desired) { Draw.flush(); Draw.blend(desired); }
    }

    private void partRegion(TextureRegion region, float x, float y, float rotation, float sx, float sy, RegionPart part) {
        if (!found(region)) return;
        float w = region.width * region.scale / 4f * scale * sx, h = region.height * region.scale / 4f * scale * sy;
        float ox = part.originX * scale * sx, oy = part.originY * scale * sy;
        float dx = px(x), dy = py(y);
        if (!safeQuad(dx, dy, Math.abs(w) + 4 * Math.abs(ox), Math.abs(h) + 4 * Math.abs(oy)) || !Float.isFinite(rotation)) return;
        Draw.rect(region, dx, dy, w, h, w / 2 + ox, h / 2 + oy, rotation);
    }

    private void tint(Unit unit) {
        Draw.color(Color.white, alpha);
        partMix.set(Color.white);
        if (unit.type.healFlash) partMix.lerp(unit.type.healColor, Mathf.clamp(finite(unit.healTime - unit.hitTime)));
        float strength = Math.max(finite(unit.hitTime), unit.type.healFlash ? Mathf.clamp(finite(unit.healTime)) : 0);
        if (unit.drownTime > 0 && unit.lastDrownFloor != null) {
            partMix.set(unit.lastDrownFloor.mapColor).mul(.83f); strength = finite(unit.drownTime) * .9f;
        }
        Draw.mixcol(partMix, strength);
        if (Vars.renderer != null && Vars.renderer.overlays != null) Vars.renderer.overlays.checkApplySelection(unit);
    }

    private void cellColor(Unit unit) {
        float health = Mathf.clamp(unit.healthf());
        float intensity = Mathf.clamp(health + Mathf.absin(Time.time, Math.max(health * 5, 1), 1 - health));
        Draw.color(unit.team.color.r * intensity, unit.team.color.g * intensity, unit.team.color.b * intensity, alpha);
    }

    private void legLine(TextureRegion region, float x1, float y1, float x2, float y2) {
        if (!found(region)) return;
        float ax = px(x1), ay = py(y1), bx = px(x2), by = py(y2);
        float stroke = Math.max(.1f, Math.abs(region.height * region.scale / 4f * scale));
        if (!safeQuad(ax, ay, stroke, stroke) || !safeQuad(bx, by, stroke, stroke)) return;
        // Arc divides by the segment length; a fully folded leg can have coincident endpoints.
        float length = Mathf.len(bx - ax, by - ay);
        if (!Float.isFinite(length) || length <= 0) return;
        Lines.stroke(stroke);
        Lines.line(region, ax, ay, bx, by, false);
    }

    private void sprite(TextureRegion region, float x, float y, float rotation, boolean flip) {
        if (found(region)) region(region, x, y, region.width * region.scale / 4f, region.height * region.scale / 4f, rotation, flip);
    }

    private void region(TextureRegion region, float x, float y, float w, float h, float rotation, boolean flip) {
        float dx = px(x), dy = py(y), dw = w * scale * (flip ? -1 : 1), dh = h * scale;
        if (found(region) && Float.isFinite(rotation) && safeQuad(dx, dy, dw, dh)) Draw.rect(region, dx, dy, dw, dh, rotation);
    }

    private void fallback(Unit unit, float pixels) {
        TextureRegion icon = unit.type.fullIcon;
        if (!found(icon)) icon = unit.type.uiIcon;
        if (!found(icon)) return;
        blend(Blending.normal);
        tint(unit);
        float ratio = pixels / Math.max(icon.width, icon.height);
        float w = icon.width * ratio, h = icon.height * ratio;
        if (safeQuad(originX, originY, w, h)) Draw.rect(icon, originX, originY, w, h, unit.rotation - 90);
    }

    private float px(float x) { return originX + (x - unitX) * scale; }
    private float py(float y) { return originY + (y - unitY) * scale; }
    private static boolean safeQuad(float x, float y, float w, float h) {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(w) && Float.isFinite(h) &&
            Math.abs((double)x) + Math.abs(y) + Math.abs(w) + Math.abs(h) < Float.MAX_VALUE / 4d;
    }
    static boolean found(TextureRegion region) {
        return region != null && region.texture != null && region.width > 0 && region.height > 0 &&
            Float.isFinite(region.scale) && region.found();
    }
}
