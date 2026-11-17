package controlgroups;

import arc.math.geom.Rect;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure prepared-data geometry, including physically insufficient windows and deliberate overflow. */
public class SpaceAllocationTest {
    @Test
    public void everyModeOrientationAndTrackingPolicyRespectsOneLongitudinalBudget() {
        TestWorld w = crowded();
        HudLayout layout = new HudLayout(w.model);
        for (var mode : ModSettings.SpaceMode.values()) for (var free : ModSettings.FreeSpaceMode.values())
            for (int direction = 0; direction < 4; direction++) for (int flags = 0; flags < 8; flags++)
                for (float scale : new float[]{.75f, 1, 2}) for (int resolution = 0; resolution < 2; resolution++) {
                    float sw = resolution == 0 ? 320 : 1920, sh = resolution == 0 ? 240 : 1080;
                    w.settings.spaceMode = mode; w.settings.freeSpaceMode = free;
                    w.settings.vertical = direction < 2; w.settings.reverse = direction % 2 == 1;
                    w.settings.internalProjection = (flags & 1) != 0;
                    w.settings.minTrackingSquare = (flags & 2) != 0;
                    w.settings.align = (flags & 4) != 0;
                    w.settings.length = 640; w.settings.breadth = 240;
                    layout.rebuild(w.settings, sw, sh, scale, false);
                    assertTrue(layout.structuralBudget >= 0);
                    assertEquals(layout.availablePrimary, layout.structuralBudget + layout.projectionReserved, .003);
                    float structural = 0, reserved = 0;
                    for (HudLayout.Box box : layout.boxes) if (box.visible) {
                        structural += box.structurePrimary; reserved += box.reservePrimary;
                        assertEquals(box.primary, box.structurePrimary + box.reservePrimary, .003);
                        assertFiniteIn(box.full, layout, sw, sh); assertFiniteIn(box.projection, layout, sw, sh);
                        assertTrue(box.content.width >= 0 && box.content.height >= 0);
                        assertTrue(Double.isFinite(box.projectionScale) && box.projectionScale >= 0);
                        assertTrue(Double.isFinite(box.projectionSafety) && box.projectionSafety >= 0);
                    }
                    assertTrue(structural <= layout.structuralBudget + .003);
                    assertEquals(layout.projectionReserved, reserved, .003);
                    assertTrue(layout.usedPrimary <= layout.availablePrimary + .003);
                }
    }

    @Test
    public void limitedAndEqualCapsIncludeInternalReservationOnce() {
        TestWorld w = crowded(); HudLayout layout = new HudLayout(w.model);
        w.settings.freeSpaceMode = ModSettings.FreeSpaceMode.FOLLOW;
        for (var mode : new ModSettings.SpaceMode[]{ModSettings.SpaceMode.LIMITED, ModSettings.SpaceMode.EQUAL}) {
            w.settings.spaceMode = mode;
            for (boolean internal : new boolean[]{false, true}) {
                w.settings.internalProjection = internal;
                layout.rebuild(w.settings, 1920, 1080, 1, false);
                float cap = layout.availablePrimary / (mode == ModSettings.SpaceMode.LIMITED ? layout.orderCount : layout.activeCount);
                for (var box : layout.boxes) if (box.visible) assertTrue(box.primary <= cap + .002f);
                if (internal) assertTrue(layout.projectionReserved > 0);
                else assertEquals(0, layout.projectionReserved, 0);
            }
        }
    }

    @Test
    public void proportionalKeepsNaturalSizesThenShrinksByNeed() {
        TestWorld w = new TestWorld(6);
        for (int i = 0; i < 6; i++) { w.add(i + 1, i); w.profiles.get(w.types.get(i)).tier = 10; }
        w.group(0, 1, 2); w.group(1, 3, 4, 5, 6); w.reconcile(false);
        w.settings.projection = false; w.settings.iconInfluence = false;
        w.settings.length = 1000;
        HudLayout l = new HudLayout(w.model); l.rebuild(w.settings, 1920, 1080, 1, false);
        assertEquals(l.boxes[0].needPrimary, l.boxes[0].primary, .001);
        assertEquals(l.boxes[1].needPrimary, l.boxes[1].primary, .001);
        w.settings.length = 160;
        l.rebuild(w.settings, 1920, 1080, 1, false);
        assertEquals(l.boxes[0].needPrimary / l.boxes[1].needPrimary, l.boxes[0].primary / l.boxes[1].primary, .001);
        assertEquals(l.availablePrimary, l.usedPrimary, .001);
    }

    @Test
    public void residualProtectsMinimumAndRetainsUnclampedPreference() {
        TestWorld w = crowded(); HudLayout l = new HudLayout(w.model);
        w.settings.freeSpaceMode = ModSettings.FreeSpaceMode.RESIDUAL;
        w.settings.freeMinimum = 50000; w.settings.projection = false;
        for (float length : new float[]{160, 640, 1600}) {
            w.settings.length = length;
            l.rebuild(w.settings, 1920, 1080, 1, false);
            assertTrue(l.boxes[10].primary + .002f >= l.length / 3);
            assertTrue(l.usedPrimary <= l.availablePrimary + .002);
        }
        assertEquals(50000, w.settings.freeMinimum, 0);
    }

    @Test
    public void externalSquareExtendsOnlyCrosswiseAndItsRealSpaceCanIncreaseFit() {
        TestWorld w = new TestWorld(5);
        for (int i = 0; i < 5; i++) { w.add(i + 1, i); w.profiles.get(w.types.get(i)).tier = 10; }
        w.group(0, 1, 2, 3, 4, 5); w.reconcile(false);
        w.settings.breadth = 70;
        HudLayout l = new HudLayout(w.model);
        for (int direction = 0; direction < 4; direction++) {
            w.settings.vertical = direction < 2; w.settings.reverse = direction % 2 == 1;
            w.settings.minTrackingSquare = false;
            l.rebuild(w.settings, 1920, 1080, 1, false);
            double fit = l.boxes[0].projectionScale;
            float p = l.boxes[0].primary;
            float width = l.width, height = l.height;
            w.settings.minTrackingSquare = true;
            l.rebuild(w.settings, 1920, 1080, 1, false);
            var box = l.boxes[0];
            assertTrue(l.boxes[0].projectionScale > fit); assertEquals(p, box.primary, 0);
            assertEquals(width, l.width, 0); assertEquals(height, l.height, 0);
            assertEquals(box.projection.width, box.projection.height, .001);
            assertTrue(box.secondary > l.breadth);
            assertFiniteIn(box.full, l, 1920, 1080);
        }
    }

    @Test
    public void manualLargestChangesOnlyFittingAndNotReservation() {
        TestWorld w = crowded(); HudLayout l = new HudLayout(w.model);
        w.settings.manualLargest = true;
        for (boolean internal : new boolean[]{false, true}) {
            w.settings.internalProjection = internal; w.settings.manualLargestSize = 100;
            l.rebuild(w.settings, 1920, 1080, 1, false);
            double first = l.boxes[0].projectionScale;
            float reserve = l.projectionReserved;
            Rect before = new Rect(l.boxes[0].full);
            w.settings.manualLargestSize = 1000;
            l.rebuild(w.settings, 1920, 1080, 1, false);
            assertTrue(l.boxes[0].projectionScale < first); assertEquals(reserve, l.projectionReserved, 0);
            assertEquals(before, l.boxes[0].full);
        }
    }

    @Test
    public void iconSizeAndCounterGapAreIndependentOfProjectionCompression() {
        TestWorld w = new TestWorld(1); w.add(1, 0); w.group(0, 1); w.reconcile(false);
        HudLayout l = new HudLayout(w.model);
        w.settings.projection = false;
        l.rebuild(w.settings, 1920, 1080, 1, false);
        var c = l.boxes[0].cells[0];
        assertTrue(c.showCount); assertEquals(36, c.iconSize, 0);
        assertEquals(2, c.icon.x - c.counterRight, .001);
        float natural = c.naturalWidth, iconSize = c.iconSize;
        w.settings.compression = 1; w.settings.adapt = false; w.settings.counterGap = 9;
        l.rebuild(w.settings, 1920, 1080, 1, false);
        assertEquals(iconSize, c.iconSize, 0); assertEquals(natural + 7, c.naturalWidth, .001);
        assertEquals(9, c.icon.x - c.counterRight, .001);
        w.settings.hideSingles = true;
        l.rebuild(w.settings, 1920, 1080, 1, false);
        assertFalse(c.showCount); assertEquals(iconSize, c.naturalWidth, 0);
        assertEquals(36, HudLayout.iconPixels(0, w.settings), 0);
        assertEquals(36, HudLayout.iconPixels(Float.NaN, w.settings), 0);
        assertEquals(72, HudLayout.iconPixels(48, w.settings), .001);
        w.settings.iconCoefficient = 1;
        assertEquals(216, HudLayout.iconPixels(48, w.settings), .001);
        assertTrue(Float.isFinite(HudLayout.iconPixels(Float.MAX_VALUE, w.settings)));
    }

    private static TestWorld crowded() {
        TestWorld w = new TestWorld(40);
        for (int i = 0; i < 80; i++) w.add(i + 1, i % 40);
        for (int group = 0; group < 10; group++) {
            w.group(group);
            for (int i = 0; i < 60; i++) w.saved[group].add(i + 1);
        }
        w.settings.unassigned = true; w.reconcile(true);
        return w;
    }

    private static void assertFiniteIn(Rect r, HudLayout l, float sw, float sh) {
        assertTrue(r.toString(), Float.isFinite(r.x) && Float.isFinite(r.y) && r.width >= 0 && r.height >= 0);
        if (r.width == 0 && r.height == 0) return;
        assertTrue(r.toString(), l.positionX + r.x >= -.01 && l.positionY + r.y >= -.01);
        assertTrue(r.toString(), l.positionX + r.x + r.width <= sw + .01);
        assertTrue(r.toString(), l.positionY + r.y + r.height <= sh + .01);
    }
}
