package controlgroups;

import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g2d.TextureAtlas;
import arc.Core;
import arc.math.geom.Rect;
import org.junit.Test;
import static org.junit.Assert.*;

public class CellPackingTest {
    private TestWorld fixture() {
        TestWorld w = new TestWorld(5);
        int[] tiers = {10, 20, 20, 40, 50}, counts = {1, 8, 1, 1, 10};
        float[] hits = {8, 20, 12, 38, 46};
        int id = 1;
        for (int i = 0; i < counts.length; i++) {
            var profile = w.profiles.get(w.types.get(i));
            profile.tier = tiers[i]; profile.order = i;
            profile.unit.hitSize = hits[i];
            for (int j = 0; j < counts[i]; j++) w.add(id++, i);
        }
        w.settings.projection = false; w.settings.unassigned = true;
        w.settings.freeSpaceMode = ModSettings.FreeSpaceMode.FOLLOW;
        w.reconcile(true);
        return w;
    }

    @Test public void sufficientSpaceKeepsCompoundBlocksAndTiersSeparate() {
        TestWorld w = fixture(); HudLayout layout = new HudLayout(w.model);
        w.settings.length = 1600; w.settings.breadth = 1000;
        for (int direction = 0; direction < 4; direction++) for (int side = 0; side < 2; side++) {
            w.settings.vertical = direction < 2; w.settings.reverse = direction % 2 == 1;
            w.settings.xFraction = w.settings.yFraction = side;
            layout.rebuild(w.settings, 1920, 1080, 1, true);
            var box = layout.boxes[GroupModel.UNASSIGNED];
            for (int i = 0; i < box.cellCount; i++) {
                var cell = box.cells[i]; compoundInside(cell, box.contentFull);
                assertEquals(cell.naturalWidth, cell.rect.width, .002);
                assertEquals(cell.naturalHeight, cell.rect.height, .002);
                for (int j = 0; j < i; j++) assertFalse(cell.rect.overlaps(box.cells[j].rect));
            }
        }
    }

    @Test public void unequalTiersUseOneContractionOfTheirNaturalCentreDistances() {
        TestWorld w = fixture(); HudLayout layout = new HudLayout(w.model);
        w.settings.length = 900; w.settings.breadth = 210;
        for (boolean vertical : new boolean[]{false, true}) {
            w.settings.vertical = vertical;
            layout.rebuild(w.settings, 1920, 1080, 1, true);
            var box = layout.boxes[GroupModel.UNASSIGNED];
            float cursor = 0, lastNatural = 0, lastActual = 0, factor = -1;
            for (int start = 0; start < box.cellCount;) {
                int end = start + 1; float size = vertical ? box.cells[start].rect.width : box.cells[start].rect.height;
                while (end < box.cellCount && box.cells[end].bucket.profile.tier == box.cells[start].bucket.profile.tier) {
                    size = Math.max(size, vertical ? box.cells[end].rect.width : box.cells[end].rect.height); end++;
                }
                float natural = cursor + size / 2;
                Rect r = box.cells[start].rect;
                // xFraction=0/yFraction=.5 gives the transverse order from the high edge in both orientations.
                float actual = vertical ? r.x + r.width / 2 : r.y + r.height / 2;
                if (start > 0) {
                    float ratio = Math.abs((actual - lastActual) / (natural - lastNatural));
                    if (factor < 0) factor = ratio; else assertEquals(factor, ratio, .0001);
                }
                lastNatural = natural; lastActual = actual; cursor += size + w.settings.tierGap; start = end;
            }
            assertTrue(factor > 0 && factor <= 1);
        }
    }

    @Test public void shapeAndTextHeightDefineTheFootprintAndWholeBlockShrinksOnlyWhenNecessary() {
        TestWorld w = fixture();
        Texture texture = new Texture() { }; texture.width = texture.height = 1024;
        for (var profile : w.profiles.all) profile.uiIcon = profile.fullIcon = new TextureRegion(texture, 32, 128);
        HudLayout layout = new HudLayout(w.model, (text, scale, cell) -> { cell.countWidth = 200 * scale; cell.countHeight = 100 * scale; });
        w.settings.length = 900; w.settings.breadth = 90;
        layout.rebuild(w.settings, 1280, 900, 1, true);
        for (int i = 0; i < layout.boxes[10].cellCount; i++) {
            var cell = layout.boxes[10].cells[i];
            assertEquals(cell.iconSize / 4 + 200 * (.65f * cell.iconSize / 24) + w.settings.counterGap, cell.naturalWidth, .002);
            assertEquals(100 * (.65f * cell.iconSize / 24) + 4, cell.naturalHeight, .002);
            assertTrue(cell.rect.width < cell.naturalWidth);
            assertEquals(cell.rect.width / cell.naturalWidth, cell.fontScale / (.65f * cell.iconSize / 24), .0001);
            compoundInside(cell, layout.boxes[10].contentFull);
        }
    }

    @Test public void orientationsModesAndOverflowKeepEveryVisualBlockInsideItsContent() {
        TestWorld w = fixture(); HudLayout layout = new HudLayout(w.model);
        for (var mode : ModSettings.SpaceMode.values()) for (var free : ModSettings.FreeSpaceMode.values())
            for (int direction = 0; direction < 4; direction++) for (int side = 0; side < 2; side++)
                for (int size = 0; size < 3; size++) for (boolean single : new boolean[]{false, true}) {
                    w.settings.spaceMode = mode; w.settings.freeSpaceMode = free;
                    w.settings.vertical = direction < 2; w.settings.reverse = direction % 2 == 1;
                    w.settings.xFraction = w.settings.yFraction = side;
                    w.settings.length = size == 0 ? 160 : size == 1 ? 640 : 1600;
                    w.settings.breadth = size == 0 ? 48 : size == 1 ? 210 : 800;
                    w.settings.hideSingles = single;
                    layout.rebuild(w.settings, size == 0 ? 320 : 1920, size == 0 ? 240 : 1080, size == 0 ? .75f : 2, true);
                    var box = layout.boxes[10];
                    for (int i = 0; i < box.cellCount; i++) compoundInside(box.cells[i], box.contentFull);
                }
    }

    @Test public void oneFullWidthBlockDoesNotCollapseAllOtherTiersOntoItsCentre() {
        TestWorld w = fixture(); w.settings.iconInfluence = false; w.settings.iconSize = 8;
        w.settings.breadth = 48; w.settings.length = 1600;
        HudLayout layout = new HudLayout(w.model, (text, scale, cell) -> {
            cell.countWidth = text.equals("10") ? 10000 : 1; cell.countHeight = 1;
        });
        layout.rebuild(w.settings, 1920, 1080, 1, true);
        var box = layout.boxes[10];
        float first = box.cells[0].rect.x + box.cells[0].rect.width / 2;
        float last = box.cells[box.cellCount - 1].rect.x + box.cells[box.cellCount - 1].rect.width / 2;
        assertEquals(box.contentFull.width, box.cells[0].rect.width, .002);
        assertTrue(Math.abs(first - last) > 1);
        for (int i = 0; i < box.cellCount; i++) compoundInside(box.cells[i], box.contentFull);
    }

    @Test public void clippedDetailedIconCanSwitchToAnOppositelyShapedUiIcon() {
        TextureAtlas previous = Core.atlas; Core.atlas = new TextureAtlas();
        try {
            TestWorld w = new TestWorld(1); w.add(1, 0); w.group(0, 1); w.reconcile(false);
            Texture texture = new Texture() { }; texture.width = texture.height = 1024;
            var profile = w.profiles.get(w.types.first());
            profile.uiIcon = new TextureRegion(texture, 64, 16);
            profile.fullIcon = new TextureRegion(texture, 128, 512);
            w.settings.projection = false; w.settings.iconInfluence = false; w.settings.iconSize = 90;
            w.settings.length = 160; w.settings.breadth = 48; w.settings.spaceMode = ModSettings.SpaceMode.LIMITED;
            HudLayout layout = new HudLayout(w.model); layout.rebuild(w.settings, 320, 240, 1, true);
            var cell = layout.boxes[0].cells[0];
            assertSame(profile.uiIcon, cell.region);
            assertEquals(4, cell.icon.width / cell.icon.height, .0001);
            compoundInside(cell, layout.boxes[0].contentFull);
        } finally { Core.atlas = previous; }
    }

    private static void compoundInside(HudLayout.Cell cell, Rect content) {
        assertIn(cell.rect, content); assertIn(cell.icon, cell.rect);
        assertTrue(Float.isFinite(cell.fontScale) && cell.fontScale >= 0);
        if (cell.showCount) {
            assertTrue(cell.counterRight - cell.countWidth >= cell.rect.x - .003f);
            assertTrue(cell.counterRight <= cell.icon.x + .003f);
            assertTrue(cell.countHeight <= cell.rect.height + .003f);
        }
    }

    private static void assertIn(Rect r, Rect parent) {
        assertTrue(r.toString(), Float.isFinite(r.x) && Float.isFinite(r.y) && r.width >= 0 && r.height >= 0);
        assertTrue(r + " outside " + parent, r.x >= parent.x - .003f && r.y >= parent.y - .003f &&
            r.x + r.width <= parent.x + parent.width + .003f && r.y + r.height <= parent.y + parent.height + .003f);
    }
}
