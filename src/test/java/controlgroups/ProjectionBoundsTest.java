package controlgroups;

import arc.Core;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureRegion;
import mindustry.entities.part.RegionPart;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
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
            type.weapons.add(weapon);
            assertTrue(ProjectionBounds.radius(type, false) >= 15 + 7 + Math.hypot(50, 12) / 2);
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
            double radius = ProjectionBounds.radius(type, true);
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
            assertTrue("Child anchor was ignored", ProjectionBounds.radius(type, true) >= 200);
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
                double radius = ProjectionBounds.radius(type, true);
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
                assertEquals(radius, ProjectionBounds.radius(type, false), .00001);
            }
        } finally { Core.atlas = previous; }
    }
}
