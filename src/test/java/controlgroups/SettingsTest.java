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
        first.profiles.save(profile);
        Vars.content = new ContentLoader();
        UnitType unrelated = new UnitType("unrelated");
        UnitType dagger = new UnitType("dagger");
        UnitProfiles reloaded = new UnitProfiles(first.store, Seq.with(unrelated, dagger));
        assertEquals(777, reloaded.get(dagger).tier);
        assertEquals(888, reloaded.get(dagger).order);
        assertEquals(3.5f, reloaded.get(dagger).resize, 0f);
    }

    @Test
    public void invalidNumbersAndColorsCannotBeCommitted() {
        assertFalse(UiControls.validNumber("NaN", 0, 100, false));
        assertFalse(UiControls.validNumber("Infinity", 0, 100, false));
        assertFalse(UiControls.validNumber("1.5", 1, 1024, true));
        assertTrue(UiControls.validNumber("0,25", 0, 100, false));
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
    }
}
