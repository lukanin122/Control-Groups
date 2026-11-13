package controlgroups;

import arc.math.geom.Rect;
import org.junit.Test;

import static org.junit.Assert.*;

public class HudLayoutTest {
    @Test
    public void fourDirectionsPackGroupsInOrderAndStayInsideBounds() {
        TestWorld w = new TestWorld(5);
        for (int i = 1; i <= 5; i++) w.add(i, i - 1);
        w.group(1, 1, 2, 3); w.group(4, 4, 5); w.group(9, 1); w.reconcile(true);
        HudLayout layout = new HudLayout(w.model);
        for (int orientation = 0; orientation < 4; orientation++) {
            w.settings.vertical = orientation < 2; w.settings.reverse = orientation % 2 == 1;
            layout.rebuild(w.settings, w.profiles, 1920, 1080, 1f, false);
            Rect previous = layout.zero;
            for (int index : new int[]{1, 4, 9}) {
                Rect r = layout.boxes[index].full;
                assertRectIn(r, layout.width, layout.height);
                if (w.settings.vertical) {
                    if (w.settings.reverse) assertTrue(r.y >= previous.y + previous.height);
                    else assertTrue(r.y + r.height <= previous.y);
                } else {
                    if (w.settings.reverse) assertTrue(r.x + r.width <= previous.x);
                    else assertTrue(r.x >= previous.x + previous.width);
                }
                previous = r;
            }
        }
    }

    @Test
    public void resizeOverflowDoesNotAlterLayoutOrAdaptedScale() {
        TestWorld w = new TestWorld(2); w.add(1, 0); w.add(2, 1);
        w.group(0, 1, 2); w.reconcile(false);
        HudLayout layout = new HudLayout(w.model);
        layout.rebuild(w.settings, w.profiles, 1000, 800, 1, false);
        Rect before = new Rect(layout.boxes[0].full);
        float scale = layout.projectionScale;
        w.profiles.get(w.types.get(0)).resize = 20;
        layout.rebuild(w.settings, w.profiles, 1000, 800, 1, false);
        assertEquals(before, layout.boxes[0].full);
        assertEquals(scale, layout.projectionScale, 0f);
        assertEquals(8f, HudLayout.compressed(8, w.settings), 0.001f);
        assertEquals(16f, HudLayout.compressed(48, w.settings), 0.001f);
    }

    @Test
    public void unassignedSlotAndCollapsedGeometryRemainFiniteAtSmallResolutions() {
        TestWorld w = new TestWorld(40);
        for (int i = 0; i < 40; i++) w.add(i + 1, i);
        w.group(0);
        for (int i = 0; i < 40; i++) w.saved[0].add(i + 1);
        w.settings.unassigned = true; w.settings.collapse = true;
        w.reconcile(true);
        HudLayout layout = new HudLayout(w.model);
        for (float scale : new float[]{0.75f, 1, 2}) {
            layout.rebuild(w.settings, w.profiles, 320, 240, scale, false);
            assertEquals(11, layout.orderCount); assertEquals(10, layout.order[0]);
            for (int i = 0; i < 20; i++) layout.animate(layout.boxes[0], w.settings, false, false, 0.1f);
            assertRectIn(layout.boxes[0].shown, layout.width, layout.height);
            assertEquals(0, layout.boxes[0].expansion, 0.001f);
            for (int i = 0; i < 20; i++) layout.animate(layout.boxes[0], w.settings, false, true, 0.1f);
            assertEquals(1, layout.boxes[0].expansion, 0.001f);
            for (int i = 0; i < layout.boxes[0].cellCount; i++) {
                Rect rect = layout.boxes[0].cells[i].rect;
                assertTrue(Float.isFinite(rect.x) && Float.isFinite(rect.y));
                assertTrue(rect.width > 0 && rect.height > 0);
            }
        }
    }

    @Test
    public void centerSideUsesLateralTieBreak() {
        assertEquals(HudLayout.Side.RIGHT, HudLayout.centerSide(0, 0, 100, 100, 1000, 1000));
        assertEquals(HudLayout.Side.TOP, HudLayout.centerSide(400, 0, 200, 100, 1000, 1000));
        assertEquals(HudLayout.Side.LEFT, HudLayout.centerSide(900, 400, 100, 200, 1000, 1000));
    }

    @Test
    public void sameTierTypesFollowTheHotbarDirection() {
        TestWorld w = new TestWorld(3);
        for (int i = 0; i < 3; i++) {
            w.add(i + 1, i);
            w.profiles.get(w.types.get(i)).tier = 10;
            w.profiles.get(w.types.get(i)).order = i + 1;
        }
        w.group(0, 1, 2, 3); w.reconcile(false);
        w.settings.projection = false;
        HudLayout layout = new HudLayout(w.model);
        for (int direction = 0; direction < 4; direction++) {
            w.settings.vertical = direction < 2; w.settings.reverse = direction % 2 == 1;
            layout.rebuild(w.settings, w.profiles, 1920, 1080, 1, false);
            var a = layout.boxes[0].cells[0].rect;
            var b = layout.boxes[0].cells[2].rect;
            if (w.settings.vertical) assertEquals(!w.settings.reverse, a.y > b.y);
            else assertEquals(w.settings.reverse, a.x > b.x);
        }
    }

    private static void assertRectIn(Rect rect, float w, float h) {
        assertTrue(rect.toString(), Float.isFinite(rect.x) && Float.isFinite(rect.y));
        assertTrue(rect.toString(), rect.x >= -0.01 && rect.y >= -0.01 && rect.width >= 0 && rect.height >= 0);
        assertTrue(rect.toString(), rect.x + rect.width <= w + 0.01 && rect.y + rect.height <= h + 0.01);
    }
}
