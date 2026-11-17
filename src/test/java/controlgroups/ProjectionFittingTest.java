package controlgroups;

import arc.math.geom.Rect;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the size contract independently of any one sprite renderer or allocation formula. */
public class ProjectionFittingTest {
    private TestWorld world(int groups) {
        TestWorld w = new TestWorld(3);
        float[] hits = {8, 46, 66};
        for (int i = 0; i < hits.length; i++) {
            w.types.get(i).hitSize = hits[i];
            // Deliberately different, conservative sprite radii, including a long weapon/part.
            var p = w.profiles.get(w.types.get(i));
            p.projectionRadius = hits[i] * .85;
            p.advancedProjectionRadius = hits[i] * 1.3;
            w.add(i + 1, i);
        }
        // This fixture changes content after construction; normal content is initialized before profiles.
        w.profiles.save(w.profiles.get(w.types.get(2)));
        for (int i = 0; i < groups; i++) w.group(i, 1, 2, 3);
        w.reconcile(false);
        return w;
    }

    @Test public void actualAreasAllowGrowthAndRelativeCompressionAtBothEndpoints() {
        TestWorld w = world(1); HudLayout l = new HudLayout(w.model);
        w.settings.internalProjection = true;
        w.settings.iconInfluence = false;
        w.settings.length = 1500; w.settings.breadth = 800;
        for (float k : new float[]{0, .2f, 1}) {
            w.settings.compression = k;
            l.rebuild(w.settings, 1920, 1080, 1, true);
            assertTrue("Fitting is still capped at the original size", l.boxes[0].projectionScale > 1);
            for (float h : new float[]{8, 46, 66}) {
                double expected = l.boxes[0].projectionFitSpan * w.settings.occupancy / l.boxes[0].projectionSafety *
                    HudLayout.compressed(h, w.settings) / HudLayout.compressed(66, w.settings);
                assertEquals(expected, l.projectionPixels(l.boxes[0], h, w.settings), .001);
            }
            if (k == 0) assertEquals(l.projectionPixels(l.boxes[0], 8, w.settings), l.projectionPixels(l.boxes[0], 66, w.settings), 0);
            if (k == 1) assertEquals(66d / 8, l.projectionPixels(l.boxes[0], 66, w.settings) / l.projectionPixels(l.boxes[0], 8, w.settings), .001);
        }
    }

    @Test public void safetyCoversRotationAndMaximumDriftAcrossGeometryPolicies() {
        for (int groups : new int[]{1, 10}) {
            TestWorld w = world(groups); HudLayout l = new HudLayout(w.model);
            for (int direction = 0; direction < 4; direction++) for (boolean internal : new boolean[]{false, true})
                for (var mode : ModSettings.SpaceMode.values()) for (boolean square : new boolean[]{false, true})
                    for (float k : new float[]{0, .2f, 1}) for (float ui : new float[]{.75f, 1, 2})
                        for (int size = 0; size < 2; size++) {
                            w.settings.vertical = direction < 2; w.settings.reverse = direction % 2 == 1;
                            w.settings.internalProjection = internal; w.settings.spaceMode = mode;
                            w.settings.minTrackingSquare = square; w.settings.compression = k;
                            w.settings.breadth = size == 0 ? 48 : 600; w.settings.length = size == 0 ? 160 : 1600;
                            w.settings.advanced = true;
                            l.rebuild(w.settings, size == 0 ? 320 : 1920, size == 0 ? 240 : 1080, ui, false);
                            for (var b : l.boxes) if (b.visible) {
                                assertEquals(Math.min(b.projectionUsable.width, b.projectionUsable.height), b.projectionFitSpan, .001);
                                for (var p : w.profiles.all) {
                                    double radius = p.advancedProjectionRadius / p.unit.hitSize * l.projectionPixels(b, p.unit.hitSize, w.settings);
                                    double drift = b.projectionFitSpan * UnitProjection.DRIFT_FRACTION;
                                    assertTrue("Rotated envelope + drift exceeds the real usable area",
                                        radius + drift <= b.projectionFitSpan / 2 + .001);
                                }
                            }
                        }
        }
    }

    @Test public void manualMaximumAndResizeDoNotFeedBackIntoGeometryOrSafety() {
        TestWorld w = world(1); HudLayout l = new HudLayout(w.model);
        w.settings.internalProjection = true;
        l.rebuild(w.settings, 1920, 1080, 1, true);
        Rect before = new Rect(l.boxes[0].full), tracking = new Rect(l.boxes[0].projectionFull);
        float reserve = l.projectionReserved, pixels = l.projectionPixels(l.boxes[0], 66, w.settings);
        double safety = l.boxes[0].projectionSafety;
        w.settings.manualLargest = true; w.settings.manualLargestSize = 1;
        w.profiles.get(w.types.get(2)).resize = 20;
        l.rebuild(w.settings, 1920, 1080, 1, true);
        assertEquals(before, l.boxes[0].full); assertEquals(tracking, l.boxes[0].projectionFull);
        assertEquals(reserve, l.projectionReserved, 0); assertEquals(safety, l.boxes[0].projectionSafety, 0);
        assertEquals(HudLayout.compressed(66, w.settings) / HudLayout.compressed(1, w.settings),
            l.projectionPixels(l.boxes[0], 66, w.settings) / pixels, .001);
        w.settings.adapt = false;
        l.rebuild(w.settings, 1920, 1080, 2, true);
        assertEquals(HudLayout.compressed(66, w.settings) * 2, l.projectionPixels(l.boxes[0], 66, w.settings), 0);
    }

    @Test public void occupancyCompressionAndMaximumNeverReserveAnImageSizedArea() {
        TestWorld w = world(1); HudLayout l = new HudLayout(w.model);
        for (boolean internal : new boolean[]{false, true}) {
            w.settings.internalProjection = internal;
            l.rebuild(w.settings, 1920, 1080, 1, true);
            Rect box = new Rect(l.boxes[0].full), projection = new Rect(l.boxes[0].projectionFull);
            for (float occupancy : new float[]{0, .01f, .5f, 1, 1.5f, 3}) for (float compression : new float[]{0, 1}) {
                w.settings.occupancy = occupancy; w.settings.compression = compression;
                l.rebuild(w.settings, 1920, 1080, 1, true);
                assertEquals(box, l.boxes[0].full); assertEquals(projection, l.boxes[0].projectionFull);
                if (occupancy == 0) assertEquals(0, l.projectionPixels(l.boxes[0], 66, w.settings), 0);
            }
        }
    }

    @Test public void eachGroupOwnsItsMaximumAreaAndSafetyIncludingUnassigned() {
        TestWorld w = world(0);
        w.group(0, 1); w.group(1, 1, 2); w.settings.unassigned = true; w.reconcile(true);
        w.settings.advanced = true; w.settings.align = false;
        w.profiles.get(w.types.get(2)).advancedProjectionRadius = 66 * 3;
        HudLayout l = new HudLayout(w.model); l.rebuild(w.settings, 1920, 1080, 1, true);
        float[] largest = {8, 46, 66}; int[] ids = {0, 1, GroupModel.UNASSIGNED};
        for (int i = 0; i < ids.length; i++) {
            var b = l.boxes[ids[i]];
            assertEquals(largest[i], b.largestHitSize, 0);
            assertEquals(b.projectionFitSpan / b.projectionSafety / HudLayout.compressed(largest[i], w.settings),
                b.projectionScale, .00001);
        }
        assertTrue(l.boxes[10].projectionSafety > l.boxes[0].projectionSafety);
        double firstScale = l.boxes[0].projectionScale, firstSafety = l.boxes[0].projectionSafety;
        double freeScale = l.boxes[10].projectionScale;
        w.profiles.get(w.types.get(2)).advancedProjectionRadius *= 2;
        l.rebuild(w.settings, 1920, 1080, 1, true);
        assertEquals(firstScale, l.boxes[0].projectionScale, 0);
        assertEquals(firstSafety, l.boxes[0].projectionSafety, 0);
        assertTrue(l.boxes[10].projectionScale < freeScale);
    }

    @Test public void sharedMaximumOverridesOnlyEachGroupsDenominator() {
        TestWorld w = world(0); w.group(0, 1); w.group(1, 1, 2, 3); w.reconcile(false);
        HudLayout l = new HudLayout(w.model); l.rebuild(w.settings, 1920, 1080, 1, true);
        float[] pixels = {l.projectionPixels(l.boxes[0], 8, w.settings), l.projectionPixels(l.boxes[1], 8, w.settings)};
        double[] safety = {l.boxes[0].projectionSafety, l.boxes[1].projectionSafety};
        Rect[] areas = {new Rect(l.boxes[0].projectionFull), new Rect(l.boxes[1].projectionFull)};
        w.settings.manualLargest = true; w.settings.manualLargestSize = 66;
        l.rebuild(w.settings, 1920, 1080, 1, true);
        for (int i = 0; i < 2; i++) {
            var b = l.boxes[i];
            assertEquals(safety[i], b.projectionSafety, 0); assertEquals(areas[i], b.projectionFull);
            assertEquals(HudLayout.compressed(b.largestHitSize, w.settings) / HudLayout.compressed(66, w.settings),
                l.projectionPixels(b, 8, w.settings) / pixels[i], .00001);
        }
        w.settings.manualLargestSize = 1;
        l.rebuild(w.settings, 1920, 1080, 1, true);
        assertTrue(l.projectionPixels(l.boxes[1], 8, w.settings) > pixels[1]);
    }

    @Test public void membershipUpdatesLocalMaximumAndEmptyGroupsClearTheirFit() {
        TestWorld w = world(0); w.group(0, 1); w.group(1, 2); w.reconcile(false);
        HudLayout l = new HudLayout(w.model); l.rebuild(w.settings, 1920, 1080, 1, true);
        assertEquals(8, l.boxes[0].largestHitSize, 0); assertEquals(46, l.boxes[1].largestHitSize, 0);
        w.group(0, 1, 3); w.reconcile(false); l.rebuild(w.settings, 1920, 1080, 1, true);
        assertEquals(66, l.boxes[0].largestHitSize, 0); assertEquals(46, l.boxes[1].largestHitSize, 0);
        w.group(0); w.reconcile(false); l.rebuild(w.settings, 1920, 1080, 1, true);
        assertFalse(l.boxes[0].visible); assertEquals(0, l.boxes[0].largestHitSize, 0);
        assertEquals(0, l.boxes[0].projectionScale, 0); assertEquals(0, l.boxes[0].projectionSafety, 0);
    }

    @Test public void occupancyAboveOneScalesImagesLinearlyWithoutGrowingHud() {
        TestWorld w = world(1); HudLayout l = new HudLayout(w.model);
        w.settings.occupancy = 1; l.rebuild(w.settings, 1920, 1080, 1, true);
        float pixels = l.projectionPixels(l.boxes[0], 66, w.settings);
        Rect full = new Rect(l.boxes[0].full), tracking = new Rect(l.boxes[0].projectionFull);
        double safety = l.boxes[0].projectionSafety;
        for (float value : new float[]{0, .8f, 1, 1.5f, 3}) {
            w.settings.occupancy = value; l.rebuild(w.settings, 1920, 1080, 1, true);
            assertEquals(pixels * value, l.projectionPixels(l.boxes[0], 66, w.settings), .001);
            assertEquals(safety, l.boxes[0].projectionSafety, 0);
            assertEquals(full, l.boxes[0].full); assertEquals(tracking, l.boxes[0].projectionFull);
        }
    }

    @Test public void collapseAndRepresentativeMotionLeaveFullFittingUnchanged() {
        TestWorld w = world(1); HudLayout l = new HudLayout(w.model); l.telemetry = true;
        w.settings.xFraction = 0; w.settings.collapse = true;
        for (boolean internal : new boolean[]{false, true}) {
            w.settings.internalProjection = internal;
            l.rebuild(w.settings, 1920, 1080, 1, false);
            Rect tracking = new Rect(l.boxes[0].projectionFull);
            float pixels = l.projectionPixels(l.boxes[0], 66, w.settings), span = l.boxes[0].projectionFitSpan;
            long builds = l.rebuilds, scans = w.model.reconciliations;
            for (int i = 0; i < 100; i++) {
                w.units.get(1).rotation = i * 45; w.units.get(1).vel.set(i % 3 - 1, 1);
                l.animate(l.boxes[0], w.settings, false, i > 50, .02f);
                assertEquals(tracking, l.boxes[0].projectionFull);
                assertTrue(l.boxes[0].projectionUsable.width + .001 >= span);
                assertTrue(l.boxes[0].projectionUsable.height + .001 >= span);
                assertEquals(pixels, l.projectionPixels(l.boxes[0], 66, w.settings), 0);
            }
            assertEquals(builds, l.rebuilds); assertEquals(scans, w.model.reconciliations);
        }
    }

    @Test public void absentZeroAndInvalidSizesRemainFinite() {
        TestWorld empty = world(0); HudLayout none = new HudLayout(empty.model);
        none.rebuild(empty.settings, 320, 240, 1, false);
        assertEquals(0, none.projectionPixels(none.boxes[0], 8, empty.settings), 0);
        TestWorld w = world(10); HudLayout l = new HudLayout(w.model);
        for (float k : new float[]{0, .2f, 1}) for (float largest : new float[]{1, Float.MAX_VALUE, Float.NaN, 0}) {
            w.settings.compression = k; w.settings.manualLargest = true; w.settings.manualLargestSize = largest;
            l.rebuild(w.settings, 1, 1, 1, false);
            assertEquals(0, l.projectionPixels(l.boxes[0], 8, w.settings), 0);
            l.rebuild(w.settings, 1920, 1080, 1, false);
            for (float hit : new float[]{0, -1, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.MAX_VALUE, Float.MIN_VALUE}) {
                float pixels = l.projectionPixels(l.boxes[0], hit, w.settings);
                assertTrue(Float.isFinite(pixels) && pixels >= 0);
                if (!Float.isFinite(hit) || hit <= 0)
                    assertEquals(l.projectionPixels(l.boxes[0], w.settings.baseSize, w.settings), pixels, 0);
            }
        }
        w.settings.baseSize = 32;
        assertEquals(32, HudLayout.referenceHit(Float.NaN, w.settings.baseSize), 0);
        assertEquals(32, HudLayout.compressed(Float.NaN, w.settings), 0);
    }
}
