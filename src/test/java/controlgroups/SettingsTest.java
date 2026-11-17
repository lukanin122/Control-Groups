package controlgroups;

import arc.Settings;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.UnitType;
import org.junit.Test;

import static org.junit.Assert.*;

public class SettingsTest {
    @Test
    public void profilesPersistByContentNameAcrossRegistrationOrderChanges() {
        TestWorld first = new TestWorld(2);
        var profile = first.profiles.get(first.types.get(0));
        profile.tier = 777; profile.order = 888; profile.resize = 3.5f;
        profile.banned = true;
        first.profiles.save(profile);
        Vars.content = new ContentLoader();
        UnitType unrelated = new UnitType("unrelated");
        UnitType dagger = new UnitType("dagger");
        UnitProfiles reloaded = new UnitProfiles(first.store, Seq.with(unrelated, dagger));
        assertEquals(777, reloaded.get(dagger).tier);
        assertEquals(888, reloaded.get(dagger).order);
        assertEquals(3.5f, reloaded.get(dagger).resize, 0f);
        assertTrue(reloaded.get(dagger).banned);
    }

    @Test
    public void v2DefaultsAndIndependentModeCollapseSurviveReload() {
        Settings store = new Settings();
        ModSettings s = new ModSettings(store);
        assertTrue(s.collapseCommand); assertFalse(s.collapseBuild);
        assertTrue(s.showBuild); assertTrue(s.fullIcons); assertTrue(s.minTrackingSquare);
        assertFalse(s.internalProjection); assertFalse(s.hideSingles); assertFalse(s.unassigned);
        assertTrue(s.iconInfluence); assertTrue(s.unassignedFirst); assertFalse(s.manualLargest);
        assertEquals(ModSettings.SpaceMode.PROPORTIONAL, s.spaceMode);
        assertEquals(ModSettings.FreeSpaceMode.LIMITED, s.freeSpaceMode);
        assertEquals(36, s.iconSize, 0); assertEquals(.2f, s.iconCoefficient, 0);
        assertEquals(32, s.freeMinimum, 0); assertEquals(2, s.counterGap, 0);
        s.useMode(false); assertFalse(s.collapse); s.setCollapse(true);
        s.useMode(true); assertTrue(s.collapse); s.setCollapse(false);
        s.freeMinimum = 10000; s.manualLargestSize = 314;
        s.manualLargest = true; s.spaceMode = ModSettings.SpaceMode.EQUAL;
        s.freeSpaceMode = ModSettings.FreeSpaceMode.RESIDUAL;
        s.hideSingles = true; s.fullIcons = false; s.iconSize = 51;
        s.iconCoefficient = .77f; s.internalProjection = true; s.minTrackingSquare = false; s.counterGap = 9;
        s.changed();
        ModSettings reloaded = new ModSettings(store);
        assertFalse(reloaded.collapseCommand); assertTrue(reloaded.collapseBuild);
        assertEquals(10000, reloaded.freeMinimum, 0); assertEquals(314, reloaded.manualLargestSize, 0);
        assertTrue(reloaded.manualLargest); assertTrue(reloaded.internalProjection);
        assertTrue(reloaded.hideSingles); assertFalse(reloaded.fullIcons); assertFalse(reloaded.minTrackingSquare);
        assertEquals(51, reloaded.iconSize, 0); assertEquals(.77f, reloaded.iconCoefficient, 0);
        assertEquals(9, reloaded.counterGap, 0);
        assertEquals(s.spaceMode, reloaded.spaceMode); assertEquals(s.freeSpaceMode, reloaded.freeSpaceMode);
    }

    @Test
    public void legacyCollapseMigratesOnlyCommandModeAndInvalidModesFallBack() {
        Settings store = new Settings();
        store.put("control-groups.collapse", false);
        store.put("control-groups.space-mode", "UNKNOWN");
        ModSettings s = new ModSettings(store);
        assertFalse(s.collapseCommand); assertFalse(s.collapseBuild);
        assertEquals(ModSettings.SpaceMode.PROPORTIONAL, s.spaceMode);
        s.initializeLargest(128); assertEquals(128, s.manualLargestSize, 0);
        s.changed(); s.initializeLargest(256); assertEquals(256, s.manualLargestSize, 0);
    }

    @Test
    public void manualLargestDefaultTracksMetadataUntilTheUserChoosesAValue() {
        Settings store = new Settings(); ModSettings s = new ModSettings(store);
        s.initializeLargest(100); s.changed();
        ModSettings reloaded = new ModSettings(store);
        reloaded.initializeLargest(200); assertEquals(200, reloaded.manualLargestSize, 0);
        reloaded.setManualLargestSize(123); reloaded.changed();
        reloaded.initializeLargest(300); assertEquals(123, reloaded.manualLargestSize, 0);
        ModSettings chosen = new ModSettings(store);
        chosen.initializeLargest(400); assertEquals(123, chosen.manualLargestSize, 0);
        s.initializeLargest(256); s.manualLargest = true; s.changed();
        s.initializeLargest(512); assertEquals(256, s.manualLargestSize, 0);
        s.setManualLargestSize(Float.MAX_VALUE); s.changed();
        assertEquals(Float.MAX_VALUE, new ModSettings(store).manualLargestSize, 0);
    }

    @Test
    public void editorPresentationPersistsWithoutInvalidatingGameGeometry() {
        Settings store = new Settings(); ModSettings s = new ModSettings(store);
        assertTrue(s.editorSchematic); assertTrue(s.editorExpanded);
        int revision = s.revision;
        s.setEditorSchematic(false); s.setEditorExpanded(false);
        assertEquals(revision, s.revision);
        ModSettings reloaded = new ModSettings(store);
        assertFalse(reloaded.editorSchematic); assertFalse(reloaded.editorExpanded);
        reloaded.setEditorExpanded(true);
        ModSettings independent = new ModSettings(store);
        assertFalse(independent.editorSchematic); assertTrue(independent.editorExpanded);
    }

    @Test
    public void invalidNumbersAndColorsCannotBeCommitted() {
        assertFalse(UiControls.validNumber("NaN", 0, 100, false));
        assertFalse(UiControls.validNumber("Infinity", 0, 100, false));
        assertFalse(UiControls.validNumber("1.5", 1, 1024, true));
        assertTrue(UiControls.validNumber("0,25", 0, 100, false));
        assertEquals("1.0E20", UiControls.format(1e20));
        assertTrue(UiControls.validNumber(Float.toString(Float.MAX_VALUE), 0, Float.MAX_VALUE, false));
        assertFalse(UiControls.validNumber("1e39", 0, Float.MAX_VALUE, false));
        assertTrue(UnitProfiles.validColor("#AB12ef"));
        assertFalse(UnitProfiles.validColor("#ab12eg"));
        assertFalse(UnitProfiles.validColor("#fff"));
        Settings store = new Settings();
        store.put("control-groups.length", Float.NaN);
        store.put("control-groups.breadth", -500);
        ModSettings settings = new ModSettings(store);
        assertEquals(640, settings.length, 0);
        assertEquals(48, settings.breadth, 0);
        settings.xFraction = 0.75f; settings.changed();
        assertEquals(0.75f, new ModSettings(store).xFraction, 0);
        settings.compression = 0; settings.changed();
        ModSettings compressed = new ModSettings(store);
        assertEquals(0, compressed.compression, 0);
        assertEquals(compressed.baseSize, HudLayout.compressed(100, compressed), 0);
    }
}
