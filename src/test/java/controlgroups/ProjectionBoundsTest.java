package controlgroups;

import arc.Core;
import arc.graphics.Texture;
import arc.graphics.Pixmap;
import arc.graphics.g2d.PixmapRegion;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureRegion;
import mindustry.entities.part.RegionPart;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.entities.part.DrawPart;
import arc.struct.ObjectMap;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectionBoundsTest {
    private TextureRegion region(int w, int h) {
        Texture texture = new Texture() { };
        texture.width = texture.height = 1024;
        return new TextureRegion(texture, w, h);
    }

    @Test public void envelopeIncludesRotatingWeaponAndFullRecoil() {
        TextureAtlas previous = Core.atlas; Core.atlas = new TextureAtlas();
        try {
            UnitType type = new TestWorld(1).types.first();
            type.region = region(80, 160);
            Weapon weapon = new Weapon();
            weapon.region = region(200, 48); weapon.x = 12; weapon.y = -9; weapon.recoil = 7;
            weapon.rotate = true;
            type.weapons.add(weapon);
            double radius = new ProjectionBounds().radius(type, false);
            assertEquals(15 + Math.hypot(25, 13), radius, .00001);
            assertTrue(radius < 15 + 7 + Math.hypot(50, 12) / 2);
        } finally { Core.atlas = previous; }
    }

    @Test public void movingGrowingPartWithOffsetOriginFitsAtEveryRotation() {
        TextureAtlas previous = Core.atlas; Core.atlas = new TextureAtlas();
        try {
            UnitType type = new TestWorld(1).types.first(); type.region = region(16, 16);
            RegionPart part = new RegionPart();
            part.regions = new TextureRegion[]{region(128, 64)};
            part.x = 7; part.y = -4; part.moveX = -12; part.moveY = 20;
            part.originX = 13; part.originY = -9;
            part.xScl = -1; part.yScl = .5f; part.growX = 2.5f; part.growY = 1;
            part.moveRot = 130; part.rotation = 37;
            type.parts.add(part);
            double radius = new ProjectionBounds().radius(type, true);
            for (int angle = 0; angle < 360; angle += 15) for (float progress : new float[]{0, .5f, 1})
                for (float growth : new float[]{0, .5f, 1}) for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) {
                    double xScale = part.xScl + part.growX * growth, yScale = part.yScl + part.growY * growth;
                    double ox = part.originX * xScale, oy = part.originY * yScale;
                    double theta = Math.toRadians(part.rotation + part.moveRot * progress);
                    double qx = sx * 16 * xScale - ox, qy = sy * 8 * yScale - oy;
                    double x = part.x + part.moveX * progress + ox + Math.cos(theta) * qx - Math.sin(theta) * qy;
                    double y = part.y + part.moveY * progress + oy + Math.sin(theta) * qx + Math.cos(theta) * qy;
                    double rotation = Math.toRadians(angle);
                    double rx = x * Math.cos(rotation) - y * Math.sin(rotation);
                    double ry = x * Math.sin(rotation) + y * Math.cos(rotation);
                    assertTrue(Math.hypot(rx, ry) <= radius + .0001);
                }
            RegionPart child = new RegionPart();
            child.x = 200; child.regions = new TextureRegion[]{region(32, 32)}; part.children.add(child);
            assertTrue("Child anchor was ignored", new ProjectionBounds().radius(type, true) >= 200);
        } finally { Core.atlas = previous; }
    }

    @Test public void invalidModdedMetadataDoesNotPoisonValidSpriteBounds() {
        TextureAtlas previous = Core.atlas; Core.atlas = new TextureAtlas();
        try {
            UnitType type = new TestWorld(1).types.first(); type.region = region(320, 160);
            type.engines.add(new UnitType.UnitEngine(Float.NaN, 100, 20, 0));
            Weapon weapon = new Weapon(); weapon.x = Float.POSITIVE_INFINITY; type.weapons.add(weapon);
            for (float hit : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
                type.hitSize = hit;
                double radius = new ProjectionBounds().radius(type, true);
                assertTrue(Double.isFinite(radius)); assertTrue(radius >= Math.hypot(80, 40) / 2);
            }
        } finally { Core.atlas = previous; }
    }

    @Test public void cachedSpriteExtentIsIndependentOfHitSize() {
        TextureAtlas previous = Core.atlas; Core.atlas = new TextureAtlas();
        try {
            UnitType type = new TestWorld(1).types.first(); type.region = region(32, 32);
            double radius = Math.hypot(8, 8) / 2;
            for (float hit : new float[]{.01f, 8, 66, Float.NaN, Float.POSITIVE_INFINITY}) {
                type.hitSize = hit;
                assertEquals(radius, new ProjectionBounds().radius(type, false), .00001);
            }
        } finally { Core.atlas = previous; }
    }

    @Test public void alphaBoundsExcludeTransparentCornersAndShareOneScanWithIconTrimming() {
        TextureAtlas previous = Core.atlas;
        PixelAtlas atlas = new PixelAtlas(); Core.atlas = atlas;
        try {
            UnitType type = new TestWorld(1).types.first();
            TextureRegion image = region(100, 100); Pixmap pixels = atlas.add(image);
            for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++)
                if ((x - 50) * (x - 50) + (y - 50) * (y - 50) <= 100) pixels.set(x, y, -1);
            type.region = type.fullIcon = image;
            ProjectionBounds bounds = new ProjectionBounds();
            double radius = bounds.radius(type, false);
            assertTrue(radius > 2.5 && radius < 3.1);
            assertEquals(radius, bounds.radius(type, true), 0);
            assertEquals(21, bounds.trim(image).width);
            assertTrue(bounds.fallbackRadius(type) < .13);
            assertEquals("Each source region must be scanned only once", 1, atlas.reads);
            for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) if ((pixels.get(x, y) & 255) != 0)
                assertTrue(Math.hypot(Math.abs(x - 49.5) + 1, Math.abs(y - 49.5) + 1) / 4 <= radius + .00001);
        } finally { Core.atlas = previous; }
    }

    @Test public void fixedSpriteTranslationKeepsItsAnchorAndAllowsSignedRecoil() {
        TextureAtlas previous = Core.atlas;
        PixelAtlas atlas = new PixelAtlas(); Core.atlas = atlas;
        try {
            UnitType type = new TestWorld(1).types.first();
            type.drawBody = type.drawCell = false;
            TextureRegion image = region(100, 100); atlas.add(image).set(50, 25, -1);
            type.region = image;
            Weapon weapon = new Weapon(); weapon.region = image; weapon.rotate = false; weapon.x = 0;
            type.weapons.add(weapon);
            ProjectionBounds bounds = new ProjectionBounds();
            weapon.recoil = -5;
            double negative = bounds.radius(type, false);
            weapon.recoil = 5;
            double positive = bounds.radius(type, false);
            assertTrue("negative=" + negative + " positive=" + positive, negative > 11 && positive < 7);
            weapon.recoil = 0; weapon.y = -6.25f;
            assertTrue(bounds.radius(type, false) < 1);
        } finally { Core.atlas = previous; }
    }

    @Test public void regionPartDataSubclassesParticipateWithoutCallingTheirDrawOverride() {
        TextureAtlas previous = Core.atlas; Core.atlas = new TextureAtlas();
        try {
            UnitType type = new TestWorld(1).types.first(); type.region = region(4, 4);
            RegionPart child = new RegionPart() {
                @Override public void draw(DrawPart.PartParams params) { throw new AssertionError("Virtual part drawing is forbidden"); }
            };
            child.x = 100; child.regions = new TextureRegion[]{region(32, 32)}; type.parts.add(child);
            assertTrue(new ProjectionBounds().radius(type, true) > 100);
        } finally { Core.atlas = previous; }
    }

    @Test public void packedMetricsSurvivePixelDisposalAndLateUiAtlasRetargeting() {
        TextureAtlas previous = Core.atlas;
        PixelAtlas atlas = new PixelAtlas(); Core.atlas = atlas;
        try {
            UnitType type = new TestWorld(1).types.first();
            var image = new TextureAtlas.AtlasRegion(region(100, 100)); image.name = "unit-" + type.name + "-ui";
            atlas.getRegions().add(image);
            Pixmap pixels = atlas.add(image);
            for (int y = 40; y < 61; y++) for (int x = 40; x < 61; x++) pixels.set(x, y, -1);
            type.region = type.uiIcon = type.fullIcon = image;
            ProjectionBounds.captureAtlas();
            assertEquals(1, atlas.reads);
            // Mindustry merges UI textures after AtlasPackEvent, changing this object's texture and UVs.
            Texture replacement = region(100, 100).texture;
            image.set(replacement, 200, 300, 100, 100);
            atlas.unavailable = true;
            UnitProfiles profiles = new UnitProfiles(new arc.Settings(), arc.struct.Seq.with(type));
            var profile = profiles.get(type);
            assertEquals(Math.hypot(11.5, 11.5) / 4, profile.projectionRadius, .000001);
            assertSame(replacement, profile.uiIcon.texture);
            assertEquals(21, profile.uiIcon.width);
            assertEquals(240 / 1024f, profile.uiIcon.u, .000001);
            assertEquals(340 / 1024f, profile.uiIcon.v, .000001);
            assertEquals("Prepared regions must not request disposed atlas pixels", 1, atlas.reads);
            assertTrue("The one-shot preparation cache must be released", new ProjectionBounds().radius(type, false) > 17);
        } finally { Core.atlas = previous; }
    }

    @Test public void emptyAndUnreadableSpritesHavePredictableSafeBounds() {
        TextureAtlas previous = Core.atlas;
        PixelAtlas atlas = new PixelAtlas(); Core.atlas = atlas;
        try {
            UnitType type = new TestWorld(1).types.first(); type.region = region(40, 80);
            atlas.add(type.region);
            ProjectionBounds empty = new ProjectionBounds();
            assertEquals(0, empty.radius(type, false), 0);
            assertSame(type.region, empty.trim(type.region));
            atlas.unavailable = true;
            ProjectionBounds unknown = new ProjectionBounds();
            assertEquals(Math.hypot(40, 80) / 8, unknown.radius(type, false), .000001);
            assertSame(type.region, unknown.trim(type.region));
        } finally { Core.atlas = previous; }
    }

    private static final class PixelAtlas extends TextureAtlas {
        private final ObjectMap<TextureRegion, PixmapRegion> images = new ObjectMap<>();
        int reads;
        boolean unavailable;
        Pixmap add(TextureRegion region) {
            Pixmap pixels = new Pixmap(ByteBuffer.allocateDirect(region.width * region.height * 4), region.width, region.height);
            images.put(region, new PixmapRegion(pixels)); return pixels;
        }
        @Override public PixmapRegion getPixmap(TextureRegion region) {
            reads++;
            if (unavailable) throw new IllegalStateException("Atlas pixmaps disposed");
            PixmapRegion pixels = images.get(region);
            if (pixels == null) throw new IllegalArgumentException("No pixels");
            return pixels;
        }
    }
}
