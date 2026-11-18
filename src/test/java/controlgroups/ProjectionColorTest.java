package controlgroups;

import arc.Core;
import arc.Settings;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.Batch;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import mindustry.content.StatusEffects;
import mindustry.entities.part.DrawPart;
import mindustry.entities.part.RegionPart;
import mindustry.entities.units.WeaponMount;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.blocks.environment.Floor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/** Source-native component drawing is the color/order oracle; no GL context or game simulation. */
public class ProjectionColorTest {
    @Test public void nativeLayerOrderAndAllColorStatesMatchForBothBaseLayers() {
        try (Fixture f = new Fixture()) {
            for (float elevation : new float[]{0, 1}) for (int state = 0; state < 5; state++) {
                f.unit.elevation = elevation;
                f.unit.hitTime = state == 1 || state == 3 ? .4f : 0;
                f.unit.healTime = state == 2 || state == 3 ? .8f : 0;
                f.unit.drownTime = state == 4 ? .65f : 0;
                f.compare(1);
            }
        }
    }

    @Test public void interpolatedAlphaHeatAndMirrorCarryMatchNativeAtEveryProgress() {
        try (Fixture f = new Fixture()) {
            f.parent.color = new Color(.2f, .6f, .1f, .25f);
            f.parent.colorTo = new Color(.8f, .1f, .7f, .85f);
            f.parent.mixColor = new Color(.8f, .2f, .1f, .15f);
            f.parent.mixColorTo = new Color(.2f, .6f, .9f, .7f);
            f.parent.heatColor.a = .3f;
            // Color values are live data; topology and Z are prepared once.
            for (float progress : new float[]{0, .5f, 1}) for (float alpha : new float[]{1, .32f}) {
                f.unit.mounts[0].warmup = f.unit.mounts[0].heat = progress;
                f.unit.healTime = .6f;
                f.compare(alpha);
            }
        }
    }

    @Test public void heatOnlyAndMissingBaseDoNotApplyBaseColorOrMix() {
        try (Fixture f = new Fixture()) {
            f.parent.drawRegion = false;
            f.parent.color = Color.red; f.parent.mixColor = Color.blue;
            f.rebuild(); f.compare(.5f);
            f.parent.drawRegion = true;
            Core.atlas.addRegion("error", Fixture.region()); Core.atlas.setErrorRegion("error");
            f.parent.regions = new TextureRegion[]{Core.atlas.find("error")}; f.parent.outline = false;
            f.rebuild(); f.compare(.5f);
        }
    }

    @Test public void childrenRestoreIncomingLayerInsteadOfInheritingParentLayer() {
        try (Fixture f = new Fixture()) {
            f.parent.layer = 110; f.parent.layerOffset = .4f;
            f.child.layer = -1; f.child.layerOffset = -.003f;
            f.rebuild(); f.compare(1);
            var plan = f.profiles.get(f.type).projectionPlan;
            assertTrue(plan.ground[0].groundZ < 100);
            assertTrue(plan.ground[plan.ground.length - 1].groundZ > 110);
        }
    }

    @Test public void projectionRestoresCallerStateAndDoesNotChangeUnitOrMounts() {
        try (Fixture f = new Fixture()) {
            Draw.color(Color.pink, .4f); Draw.mixcol(Color.green, .3f); Draw.blend(Blending.additive);
            Draw.scl(2, 3); Draw.z(812);
            float color = Draw.getColorPacked(), mix = Draw.getMixColorPacked();
            float ux = f.unit.x, uy = f.unit.y, rotation = f.unit.rotation, recoil = f.unit.mounts[0].recoil;
            f.projection.draw(f.unit, 0, 50, 60, 100, 0, 8, 0, true, .5f);
            assertEquals(color, Draw.getColorPacked(), 0); assertEquals(mix, Draw.getMixColorPacked(), 0);
            assertSame(Blending.additive, Draw.getBlend()); assertEquals(2, Draw.xscl, 0); assertEquals(3, Draw.yscl, 0);
            assertEquals(812, Draw.z(), 0); assertEquals(ux, f.unit.x, 0); assertEquals(uy, f.unit.y, 0);
            assertEquals(rotation, f.unit.rotation, 0); assertEquals(recoil, f.unit.mounts[0].recoil, 0);
        }
    }

    @Test public void malformedProgressSkipsOnlyItsSubtreeWithoutPoisoningOtherSprites() {
        try (Fixture f = new Fixture()) {
            f.parent.progress = params -> Float.NaN;
            f.projection.draw(f.unit, 0, 0, 0, 8, 0, 8, 0, true, 1);
            assertTrue(f.trace.commands.stream().anyMatch(c -> c.region == f.type.region));
            assertFalse(f.trace.commands.stream().anyMatch(c -> c.region == f.parent.regions[0] || c.region == f.child.regions[0]));
            assertTrue(f.trace.commands.stream().allMatch(c -> Float.isFinite(c.x) && Float.isFinite(c.y)));
        }
    }

    @Test public void disabledHealFlashUsesStableWhiteDamageInsteadOfSharedTemporaryColor() {
        try (Fixture f = new Fixture()) {
            f.type.healFlash = false; f.unit.hitTime = .5f; f.unit.healTime = 1;
            arc.util.Tmp.c1.set(Color.red);
            f.projection.draw(f.unit, 0, 0, 0, 8, 0, 8, 0, true, 1);
            Command body = f.trace.commands.stream().filter(c -> c.region == f.type.region).findFirst().orElseThrow();
            Color mix = new Color().abgr8888(body.mix);
            assertEquals(1, mix.r, .01); assertEquals(1, mix.g, .01); assertEquals(1, mix.b, .01);
            assertEquals(.5, mix.a, .01);
        }
    }

    @Test public void engineColorsRetainTheirAlphaWithoutChangingExistingGeometry() {
        try (Fixture f = new Fixture()) {
            var circle = Core.atlas.addRegion("circle", Fixture.region());
            var engine = new UnitType.UnitEngine(4, -10, 6, 90); engine.color = new Color(.2f, .4f, .8f, .5f);
            f.type.engines.add(engine); f.type.useEngineElevation = false;
            f.type.engineColorInner = new Color(.7f, .6f, .3f, .4f); f.rebuild();
            f.unit.hitTime = .8f; f.unit.healTime = 1; f.unit.drownTime = .7f;
            f.projection.draw(f.unit, 0, 100, 120, 16, 0, 8, 0, true, .5f);
            List<Command> circles = f.trace.commands.stream().filter(c -> c.region == circle).toList();
            assertEquals(2, circles.size());
            Color outer = new Color().abgr8888(circles.get(0).color), inner = new Color().abgr8888(circles.get(1).color);
            assertEquals(.2, outer.r, .009); assertEquals(.8, outer.b, .009); assertEquals(.25, outer.a, .009);
            assertEquals(.7, inner.r, .009); assertEquals(.3, inner.b, .009); assertEquals(.2, inner.a, .009);
            float radius = 12 * (.8f + arc.math.Mathf.absin(arc.util.Time.time, 2, .2f));
            assertEquals(108, circles.get(0).x + circles.get(0).w / 2, .0001);
            assertEquals(100, circles.get(0).y + circles.get(0).h / 2, .0001);
            assertEquals(radius * 2, circles.get(0).w, .0001);
            assertEquals(circles.get(0).x + circles.get(0).w / 2, circles.get(1).x + circles.get(1).w / 2, .0001);
            assertEquals(circles.get(0).y + circles.get(0).h / 2, circles.get(1).y + circles.get(1).h / 2, .0001);
            assertEquals(radius * 2 * .45f, circles.get(1).w, .0001);
            assertEquals(Color.clearFloatBits, circles.get(0).mix, 0); assertEquals(Color.clearFloatBits, circles.get(1).mix, 0);
        }
    }

    @Test public void deferredUiBatchFlushesOnlyAtBlendBoundariesIncludingCallerVertices() {
        try (Fixture f = new Fixture()) {
            class Deferred extends Batch {
                final List<Blending> pending = new ArrayList<>();
                Blending last;
                int runs, submissions;
                @Override protected void setBlending(Blending blend) { blending = blend; } // Actual SpriteBatch behavior.
                @Override protected void draw(TextureRegion region, float x, float y, float ox, float oy, float w, float h, float rotation) {
                    if (last != blending) { last = blending; runs++; }
                    pending.add(blending);
                }
                @Override protected void draw(Texture texture, float[] vertices, int offset, int count) { }
                @Override protected void flush() {
                    if (!pending.isEmpty()) submissions++;
                    for (Blending requested : pending) assertSame("Buffered sprites submitted with the wrong blend", requested, blending);
                    pending.clear();
                }
            }
            Deferred batch = new Deferred(); Core.batch = batch;
            Draw.blend(Blending.additive); Draw.rect(f.type.region, 0, 0);
            f.projection.draw(f.unit, 0, 0, 0, 8, 0, 8, 0, true, 1);
            assertSame(Blending.additive, Draw.getBlend());
            Draw.rect(f.type.region, 0, 0); Draw.flush();
            assertEquals("Consecutive heat sprites must share one submission", batch.runs, batch.submissions);
        }
    }

    @Test public void layersThatCollideInArcSortKeyKeepNativeInsertionOrder() {
        try (Fixture f = new Fixture()) {
            RegionPart first = Fixture.part(), second = Fixture.part();
            first.layer = Math.nextUp(60f); second.layer = 60;
            first.outline = second.outline = false;
            assertEquals(Float.floatToRawIntBits(first.layer + 16f), Float.floatToRawIntBits(second.layer + 16f));
            f.type.parts.clear(); f.type.parts.add(first, second); f.rebuild(); f.compare(1);
            var commands = f.profiles.get(f.type).projectionPlan.ground;
            int a = -1, b = -1;
            for (int i = 0; i < commands.length; i++) {
                if (commands[i].region == first.regions[0]) a = i;
                if (commands[i].region == second.regions[0]) b = i;
            }
            assertTrue(a >= 0 && b > a);
        }
    }

    static final class Command {
        final TextureRegion region;
        final float color, mix, z, x, y, w, h;
        final Blending blend;
        Command(TextureRegion region, float x, float y, float w, float h) {
            this.region = region; this.x = x; this.y = y;
            this.w = w; this.h = h;
            color = Draw.getColorPacked(); mix = Draw.getMixColorPacked(); z = Draw.z(); blend = Draw.getBlend();
        }
    }

    static class Trace extends Batch {
        final List<Command> commands = new ArrayList<>();
        boolean capture = true;
        @Override protected void draw(TextureRegion region, float x, float y, float ox, float oy, float w, float h, float rotation) {
            if (capture) commands.add(new Command(region, x, y, w, h));
        }
        @Override protected void draw(Texture texture, float[] vertices, int offset, int count) { }
        @Override protected void flush() { }
    }

    static final class Fixture implements AutoCloseable {
        final Batch previousBatch = Core.batch;
        final TextureAtlas previousAtlas = Core.atlas;
        final float previousScale = Draw.scl;
        final StatusEffect previousBurning = StatusEffects.burning, previousMelting = StatusEffects.melting;
        final UnitType type;
        final TestWorld.FakeUnit unit;
        final RegionPart parent, child;
        final Trace trace = new Trace();
        UnitProfiles profiles;
        UnitProjection projection;

        Fixture() {
            TestWorld world = new TestWorld(1);
            type = world.types.first(); unit = world.add(1, 0);
            Core.atlas = new TextureAtlas(); Core.batch = trace; Draw.scl = .25f; Draw.reset(); Draw.scl(1);
            StatusEffects.burning = new StatusEffect("fixture-burning"); StatusEffects.melting = new StatusEffect("fixture-melting");
            type.region = region(); type.outlineRegion = region(); type.cellRegion = region();
            type.groundLayer = 60; type.flyingLayer = 115;
            Weapon weapon = new Weapon(); weapon.shadow = 0; weapon.layerOffset = .1f;
            weapon.region = region(); weapon.outlineRegion = region(); weapon.cellRegion = region(); weapon.heatRegion = region();
            weapon.heatColor.a = .2f; weapon.reload = 20;
            parent = part(); parent.mirror = true; parent.under = true; parent.layerOffset = -.002f;
            child = part(); child.layerOffset = -.003f; parent.children.add(child);
            weapon.parts.add(parent); weapon.parts.add(part());
            type.weapons.add(weapon);
            RegionPart absolute = part(); absolute.layer = 90; type.parts.add(absolute);
            unit.mounts = new WeaponMount[]{new WeaponMount(weapon)};
            unit.mounts[0].warmup = unit.mounts[0].heat = .5f;
            unit.lastDrownFloor = new Floor("fixture-water"); unit.lastDrownFloor.mapColor.set(.2f, .45f, .8f, 1);
            unit.rotation = 90;
            rebuild();
        }

        static TextureRegion region() {
            Texture texture = new Texture() { }; texture.width = texture.height = 64;
            return new TextureRegion(texture, 32, 32);
        }

        static RegionPart part() {
            RegionPart part = new RegionPart(); part.turretShading = false;
            part.regions = new TextureRegion[]{region()}; part.outlines = new TextureRegion[]{region()}; part.heat = region();
            return part;
        }

        void rebuild() {
            profiles = new UnitProfiles(new Settings(), Seq.with(type)); projection = new UnitProjection(profiles);
        }

        void compare(float alpha) {
            trace.commands.clear(); Draw.reset(); Draw.scl(1);
            float z = unit.elevation > .5f ? type.flyingLayer : type.groundLayer + .002f;
            Draw.z(z); type.drawOutline(unit); type.drawWeaponOutlines(unit);
            type.drawBody(unit); type.drawCell(unit); type.drawWeapons(unit);
            for (DrawPart part : type.parts) {
                var mount = unit.mounts[part.weaponIndex];
                DrawPart.params.set(mount.warmup, mount.reload / mount.weapon.reload, mount.smoothReload,
                    mount.heat, mount.recoil, mount.charge, unit.x, unit.y, unit.rotation);
                type.applyColor(unit); part.draw(DrawPart.params);
            }
            List<Command> nativeCommands = new ArrayList<>(trace.commands);
            nativeCommands.sort(Comparator.comparingInt(c -> Float.floatToRawIntBits(c.z + 16f)));
            trace.commands.clear();
            projection.draw(unit, 0, unit.x, unit.y, 8, 0, 8, 0, true, alpha);
            assertEquals("draw count", nativeCommands.size(), trace.commands.size());
            Color expected = new Color(), actual = new Color();
            for (int i = 0; i < nativeCommands.size(); i++) {
                Command a = nativeCommands.get(i), b = trace.commands.get(i);
                assertSame("region order at " + i, a.region, b.region); assertSame("blend at " + i, a.blend, b.blend);
                expected.abgr8888(a.color); actual.abgr8888(b.color);
                assertEquals("R at " + i, expected.r, actual.r, .009);
                assertEquals("G at " + i, expected.g, actual.g, .009);
                assertEquals("B at " + i, expected.b, actual.b, .009);
                assertEquals("alpha at " + i, expected.a * alpha, actual.a, .009);
                assertEquals("mix at " + i, Float.floatToRawIntBits(a.mix), Float.floatToRawIntBits(b.mix));
            }
        }

        @Override public void close() {
            Draw.reset(); Draw.scl(1); Draw.scl = previousScale;
            Core.batch = previousBatch; Core.atlas = previousAtlas;
            StatusEffects.burning = previousBurning; StatusEffects.melting = previousMelting;
        }
    }
}
