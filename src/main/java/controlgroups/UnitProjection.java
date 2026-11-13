package controlgroups;

import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.util.Log;
import arc.util.Time;
import mindustry.content.StatusEffects;
import mindustry.entities.units.WeaponMount;
import mindustry.entities.part.DrawPart;
import mindustry.entities.part.DrawPart.PartParams;
import mindustry.entities.part.RegionPart;
import mindustry.gen.Crawlc;
import mindustry.gen.Legsc;
import mindustry.gen.Mechc;
import mindustry.gen.Segmentc;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/** Read-only sprite renderer. Never calls UnitType/Weapon/Ability virtual drawing code. */
public final class UnitProjection {
    private static final Color FIRE = Color.valueOf("ff795e");
    private final IntSet failed = new IntSet();
    private final Vec2 offset = new Vec2();
    private final Color partColor = new Color(), partMix = new Color();
    private final PartParams[] partParams = new PartParams[17];
    private float originX, originY, scale, unitX, unitY, alpha;
    private final float[] driftX = new float[11], driftY = new float[11];

    public UnitProjection() {
        for (int i = 0; i < partParams.length; i++) partParams[i] = new PartParams();
    }

    public void clear() {
        java.util.Arrays.fill(driftX, 0); java.util.Arrays.fill(driftY, 0);
        failed.clear();
    }

    public void draw(Unit unit, int group, float x, float y, float pixels, float zone, float delta, boolean advanced, float alpha) {
        if (unit == null || !unit.isValid() || !Float.isFinite(pixels) || pixels <= 0) return;
        float color = Draw.getColorPacked(), mix = Draw.getMixColorPacked(), z = Draw.z();
        float xs = Draw.xscl, ys = Draw.yscl, stroke = Lines.getStroke();
        Blending blend = Draw.getBlend();
        this.alpha = alpha;
        float vx = Mathf.clamp(unit.vel.x, -1f, 1f) * zone * 0.07f;
        float vy = Mathf.clamp(unit.vel.y, -1f, 1f) * zone * 0.07f;
        float interpolation = 1f - (float)Math.exp(-12f * delta);
        driftX[group] = Mathf.lerp(driftX[group], vx, interpolation);
        driftY[group] = Mathf.lerp(driftY[group], vy, interpolation);
        originX = x + driftX[group]; originY = y + driftY[group];
        unitX = unit.x; unitY = unit.y;
        float hitSize = Float.isFinite(unit.type.hitSize) && unit.type.hitSize > 0 ? unit.type.hitSize : 8f;
        scale = pixels / hitSize;
        try {
            Draw.scl(1f); Draw.blend(Blending.normal);
            if (failed.contains(unit.type.id)) {
                fallback(unit, pixels);
            } else {
                render(unit, advanced);
            }
        } catch (RuntimeException exception) {
            if (failed.add(unit.type.id)) Log.warn("[Control Groups] Projection fallback for @: @", unit.type.name, exception.toString());
            Draw.reset(); Draw.scl(1f); Draw.blend(Blending.normal);
            fallback(unit, pixels);
        } finally {
            Draw.color(color); Draw.mixcol(mix); Draw.blend(blend);
            Draw.scl(xs, ys); Draw.z(z); Lines.stroke(stroke);
        }
    }

    private void render(Unit unit, boolean advanced) {
        UnitType type = unit.type;
        tint(unit);
        // Packed multi-segment bodies are a safe recognisable fallback for special geometries.
        if (unit instanceof Crawlc || unit instanceof Segmentc || !found(type.region)) {
            sprite(found(type.fullIcon) ? type.fullIcon : type.uiIcon, unit.x, unit.y, unit.rotation - 90, false);
            return;
        }
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
            float sin = Mathf.lerp(Mathf.sin(mech.walkExtend(true), 2f / Mathf.PI, 1), 0, unit.elevation);
            float extension = Mathf.lerp(mech.walkExtend(false), 0, unit.elevation);
            float boost = unit.elevation * 2;
            for (int side = -1; side <= 1; side += 2) {
                if (found(type.legRegion)) region(type.legRegion,
                    unit.x + Angles.trnsx(mech.baseRotation(), extension * side - boost, -boost * side),
                    unit.y + Angles.trnsy(mech.baseRotation(), extension * side - boost, -boost * side),
                    type.legRegion.width * type.legRegion.scale / 4f * side,
                    type.legRegion.height * type.legRegion.scale / 4f * (1 - Math.max(-sin * side, 0) * 0.5f),
                    mech.baseRotation() - 90 + 35 * side * unit.elevation, false);
            }
            sprite(type.baseRegion, unit.x, unit.y, mech.baseRotation() - 90, false);
        }
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
        if (advanced && (unit.elevation > 0.01f || !type.useEngineElevation)) {
            Draw.mixcol();
            for (var engine : type.engines) {
                offset.set(engine.x, engine.y).rotate(unit.rotation - 90);
                float radius = engine.radius * (0.8f + Mathf.absin(Time.time, 2f, 0.2f)) * scale * (type.useEngineElevation ? unit.elevation : 1f);
                Draw.color(engine.color != null ? engine.color : type.engineColor == null ? unit.team.color : type.engineColor, alpha);
                Fill.circle(px(unit.x + offset.x), py(unit.y + offset.y), radius);
                Draw.color(Color.white, alpha);
                Fill.circle(px(unit.x + offset.x), py(unit.y + offset.y), radius * 0.45f);
            }
        }
        tint(unit);
        for (WeaponMount mount : unit.mounts) if (!mount.weapon.top) weapon(unit, mount, advanced);
        tint(unit);
        if (advanced) unitParts(unit, true);
        if (type.drawBody) {
            sprite(type.outlineRegion, unit.x, unit.y, unit.rotation - 90, false);
            sprite(type.region, unit.x, unit.y, unit.rotation - 90, false);
        }
        if (type.drawCell && found(type.cellRegion)) {
            cellColor(unit);
            sprite(type.cellRegion, unit.x, unit.y, unit.rotation - 90, false);
        }
        for (WeaponMount mount : unit.mounts) if (mount.weapon.top) weapon(unit, mount, advanced);
        if (advanced) unitParts(unit, false);
        Draw.mixcol();
        if (unit.hasEffect(StatusEffects.burning) || unit.hasEffect(StatusEffects.melting)) {
            Draw.color(FIRE, alpha * (0.3f + Mathf.absin(Time.time, 3f, 0.4f)));
            Lines.stroke(Math.max(1f, scale));
            Lines.circle(originX, originY, unit.hitSize * scale * 0.45f);
        }
        if (advanced && unit.shield > 0 && type.drawShields) {
            Draw.color(unit.team.color, alpha * 0.6f);
            Lines.stroke(Math.max(1f, scale));
            Lines.poly(originX, originY, 6, unit.hitSize * scale * 0.65f, Time.time * 0.2f);
        }
    }

    private void weapon(Unit unit, WeaponMount mount, boolean advanced) {
        var weapon = mount.weapon;
        float rotation = unit.rotation - 90;
        float weaponRotation = rotation + (weapon.rotate ? mount.rotation : weapon.baseRotation);
        float recoil = Mathf.pow(mount.recoil, weapon.recoilPow) * weapon.recoil;
        float wx = unit.x + Angles.trnsx(rotation, weapon.x, weapon.y) + Angles.trnsx(weaponRotation, 0, -recoil);
        float wy = unit.y + Angles.trnsy(rotation, weapon.x, weapon.y) + Angles.trnsy(weaponRotation, 0, -recoil);
        if (advanced) for (int i = 0; i < weapon.parts.size; i++) {
            DrawPart part = weapon.parts.get(i);
            if (part.under) {
                params(mount, wx, wy, weaponRotation + 90, part.recoilIndex);
                partParams[0].sideMultiplier = weapon.flipSprite ? -1 : 1;
                tint(unit); part(part, 0);
            }
        }
        tint(unit);
        sprite(weapon.outlineRegion, wx, wy, weaponRotation, weapon.flipSprite);
        sprite(weapon.region, wx, wy, weaponRotation, weapon.flipSprite);
        if (found(weapon.cellRegion)) {
            cellColor(unit); sprite(weapon.cellRegion, wx, wy, weaponRotation, weapon.flipSprite);
        }
        if (advanced && mount.heat > 0 && found(weapon.heatRegion)) {
            Draw.mixcol(); Draw.color(weapon.heatColor, alpha * mount.heat);
            Draw.blend(Blending.additive); sprite(weapon.heatRegion, wx, wy, weaponRotation, weapon.flipSprite); Draw.blend();
        }
        if (advanced) for (int i = 0; i < weapon.parts.size; i++) {
            DrawPart part = weapon.parts.get(i);
            if (!part.under) {
                params(mount, wx, wy, weaponRotation + 90, part.recoilIndex);
                partParams[0].sideMultiplier = weapon.flipSprite ? -1 : 1;
                tint(unit); part(part, 0);
            }
        }
    }

    private void params(WeaponMount mount, float x, float y, float rotation, int recoilIndex) {
        if (mount == null) partParams[0].set(0, 0, 0, 0, 0, 0, x, y, rotation);
        else partParams[0].set(mount.warmup, mount.weapon.reload > 0 ? mount.reload / mount.weapon.reload : 0,
            mount.smoothReload, mount.heat, recoilIndex >= 0 && mount.recoils != null && recoilIndex < mount.recoils.length ?
                mount.recoils[recoilIndex] : mount.recoil, mount.charge, x, y, rotation);
    }

    private void unitParts(Unit unit, boolean under) {
        for (int i = 0; i < unit.type.parts.size; i++) {
            DrawPart part = unit.type.parts.get(i);
            if (part.under != under) continue;
            WeaponMount mount = part.weaponIndex >= 0 && part.weaponIndex < unit.mounts.length ? unit.mounts[part.weaponIndex] : null;
            params(mount, unit.x, unit.y, unit.rotation, part.recoilIndex);
            tint(unit); part(part, 0);
        }
    }

    /** RegionPart data adapter: no polymorphic draw(), world light, abilities or custom child draw calls. */
    private void part(DrawPart raw, int depth) {
        if (raw == null || raw.getClass() != RegionPart.class || depth >= partParams.length - 1) return;
        RegionPart part = (RegionPart)raw;
        PartParams params = partParams[depth];
        float progress = part.progress.getClamp(params, part.clampProgress);
        float grow = part.growProgress.getClamp(params, part.clampProgress);
        if (!Float.isFinite(progress) || !Float.isFinite(grow)) return;
        float mx = part.moveX * progress, my = part.moveY * progress, mr = part.moveRot * progress + part.rotation;
        float gx = part.growX * grow, gy = part.growY * grow;
        for (int i = 0; i < part.moves.size; i++) {
            var move = part.moves.get(i); float p = move.progress.getClamp(params, part.clampProgress);
            mx += move.x * p; my += move.y * p; mr += move.rot * p; gx += move.gx * p; gy += move.gy * p;
        }
        int count = part.mirror && params.sideOverride == -1 ? 2 : 1;
        for (int side = 0; side < count; side++) {
            int index = params.sideOverride == -1 ? side : params.sideOverride;
            float sign = (index == 0 ? 1 : -1) * params.sideMultiplier;
            float rx = params.x + Angles.trnsx(params.rotation - 90, (part.x + mx) * sign, part.y + my);
            float ry = params.y + Angles.trnsy(params.rotation - 90, (part.x + mx) * sign, part.y + my);
            float rotation = mr * sign + params.rotation - 90;
            float sx = (part.xScl + gx) * sign, sy = part.yScl + gy;
            float drawX = rx, drawY = ry;
            if (part.originX != 0 || part.originY != 0) {
                drawX -= Angles.trnsx(params.rotation - 90, -part.originX * sx, -part.originY * sy) + part.originX * sx;
                drawY -= Angles.trnsy(params.rotation - 90, -part.originX * sx, -part.originY * sy) + part.originY * sy;
            }
            float savedColor = Draw.getColorPacked(), savedMix = Draw.getMixColorPacked();
            if (part.outline && part.drawRegion && part.outlines.length > 0) {
                partRegion(part.outlines[Math.min(index, part.outlines.length - 1)], drawX, drawY, rotation, sx, sy, part);
            }
            if (part.color != null) {
                partColor.set(part.color);
                if (part.colorTo != null) partColor.lerp(part.colorTo, progress);
                Draw.color(partColor, alpha * partColor.a);
            }
            if (part.mixColor != null) {
                partMix.set(part.mixColor);
                if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress);
                Draw.mixcol(partMix, partMix.a);
            }
            Draw.blend(part.blending);
            if (part.drawRegion && part.regions.length > 0) partRegion(part.regions[Math.min(index, part.regions.length - 1)], drawX, drawY, rotation, sx, sy, part);
            Draw.blend();
            if (found(part.heat)) {
                Draw.color(part.heatColor, alpha * part.heatProgress.getClamp(params, part.clampProgress));
                Draw.blend(Blending.additive); partRegion(part.heat, drawX, drawY, rotation, sx, sy, part); Draw.blend();
            }
            Draw.color(savedColor); Draw.mixcol(savedMix);
            PartParams child = partParams[depth + 1].set(params.warmup, params.reload, params.smoothReload, params.heat,
                params.recoil, params.charge, rx, ry, mr * sign + params.rotation);
            child.sideOverride = index; child.sideMultiplier = params.sideMultiplier; child.life = params.life;
            for (int i = 0; i < part.children.size; i++) part(part.children.get(i), depth + 1);
        }
    }

    private void partRegion(TextureRegion region, float x, float y, float rotation, float sx, float sy, RegionPart part) {
        if (!found(region)) return;
        float w = region.width * region.scale / 4f * scale * sx, h = region.height * region.scale / 4f * scale * sy;
        Draw.rect(region, px(x), py(y), w, h, w / 2 + part.originX * scale * sx, h / 2 + part.originY * scale * sy, rotation);
    }

    private void tint(Unit unit) {
        Draw.color(Color.white, alpha);
        Draw.mixcol(Color.white, Mathf.clamp(unit.hitTime));
    }

    private void cellColor(Unit unit) {
        float health = Mathf.clamp(unit.healthf());
        float intensity = Mathf.clamp(health + Mathf.absin(Time.time, Math.max(health * 5, 1), 1 - health));
        Draw.color(unit.team.color.r * intensity, unit.team.color.g * intensity, unit.team.color.b * intensity, alpha);
    }

    private void legLine(TextureRegion region, float x1, float y1, float x2, float y2) {
        if (!found(region)) return;
        Lines.stroke(Math.max(0.1f, region.height * region.scale / 4f * scale));
        Lines.line(region, px(x1), py(y1), px(x2), py(y2), false);
    }

    private void sprite(TextureRegion region, float x, float y, float rotation, boolean flip) {
        if (found(region)) region(region, x, y, region.width * region.scale / 4f, region.height * region.scale / 4f, rotation, flip);
    }

    private void region(TextureRegion region, float x, float y, float w, float h, float rotation, boolean flip) {
        if (found(region)) Draw.rect(region, px(x), py(y), w * scale * (flip ? -1 : 1), h * scale, rotation);
    }

    private void fallback(Unit unit, float pixels) {
        TextureRegion icon = unit.type.fullIcon;
        if (!found(icon)) icon = unit.type.uiIcon;
        if (!found(icon)) return;
        Draw.color(Color.white, alpha); Draw.mixcol(Color.white, Mathf.clamp(unit.hitTime));
        float ratio = pixels / Math.max(icon.width, icon.height);
        Draw.rect(icon, originX, originY, icon.width * ratio, icon.height * ratio, unit.rotation - 90);
    }

    private float px(float x) { return originX + (x - unitX) * scale; }
    private float py(float y) { return originY + (y - unitY) * scale; }
    private static boolean found(TextureRegion region) {
        return region != null && region.texture != null && region.width > 0 && region.height > 0 && region.found();
    }
}
