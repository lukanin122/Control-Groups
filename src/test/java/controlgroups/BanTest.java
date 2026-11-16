package controlgroups;

import arc.Settings;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;
import org.junit.Test;

import static org.junit.Assert.*;

public class BanTest {
    @Test
    public void flagsAndEveryCoreUnitAreBannedWithoutMutatingContent() {
        Vars.content = new ContentLoader();
        Seq<UnitType> types = new Seq<>();
        UnitType normal = new UnitType("ordinary"); types.add(normal);
        UnitType hidden = new UnitType("hidden"); hidden.hidden = true; types.add(hidden);
        UnitType internal = new UnitType("internal"); internal.internal = true; types.add(internal);
        UnitType forbidden = new UnitType("forbidden"); forbidden.playerControllable = false; types.add(forbidden);
        UnitType modCore = new UnitType("some-mod-core-avatar"); types.add(modCore);
        CoreBlock core = new CoreBlock("some-mod-core"); core.unitType = modCore;
        UnitProfiles profiles = new UnitProfiles(new Settings(), types, Seq.<Block>with(core));
        assertEquals(types.size, profiles.all.size);
        assertTrue(profiles.allowed(normal));
        for (UnitType type : new UnitType[]{hidden, internal, forbidden, modCore}) {
            assertTrue(profiles.get(type).banned); assertTrue(profiles.get(type).defaultBanned);
        }
        assertTrue(hidden.hidden); assertTrue(internal.internal); assertFalse(forbidden.playerControllable);
        assertSame(modCore, core.unitType);
        var p = profiles.get(forbidden); p.banned = false; profiles.save(p);
        assertTrue(profiles.allowed(forbidden)); assertFalse(forbidden.playerControllable);
    }

    @Test
    public void banRemovesMembershipCountsStatesRepresentativesAndExtents() {
        TestWorld w = new TestWorld(3);
        w.add(1, 0); w.add(2, 1); w.add(3, 2);
        w.group(0, 1, 2); w.group(1, 2); w.group(2, 3);
        w.reconcile(true); w.sample(0);
        var largest = w.profiles.get(w.types.get(2));
        largest.banned = true; w.profiles.save(largest);
        var p = w.profiles.get(w.types.get(1)); p.banned = true; w.profiles.save(p);
        w.reconcile(true); w.sample(.1);
        assertEquals(1, w.model.observedUnits());
        assertEquals(1, w.model.groups[0].members.size); assertEquals(1, w.model.groups[0].types.size);
        assertEquals("1", w.model.groups[0].types.first().countText);
        assertTrue(w.model.groups[1].empty()); assertNull(w.model.groups[1].representative);
        assertTrue(w.model.groups[2].empty()); assertTrue(w.model.groups[10].empty());
        assertEquals(w.types.get(0).hitSize, w.profiles.largestHitSize, 0);
        assertEquals(2, w.saved[0].size); assertEquals(2, w.saved[1].first());
        p.banned = false; w.profiles.save(p); w.reconcile(true); w.sample(.2);
        assertEquals(2, w.model.observedUnits()); assertEquals(2, w.model.groups[0].members.size);
        assertFalse(w.model.groups[1].empty());
        for (var profile : w.profiles.all) { profile.banned = true; w.profiles.save(profile); }
        w.reconcile(true); w.sample(.3);
        assertEquals(0, w.model.observedUnits()); assertEquals(0, w.profiles.largestHitSize, 0);
    }

    @Test
    public void explicitOverrideSurvivesDefaultFlagAndRegistrationOrderChanges() {
        Vars.content = new ContentLoader();
        UnitType type = new UnitType("mod-special"); type.hidden = true;
        Settings store = new Settings();
        UnitProfiles profiles = new UnitProfiles(store, Seq.with(type));
        profiles.get(type).banned = false; profiles.save(profiles.get(type));
        Vars.content = new ContentLoader();
        new UnitType("different-registration-order");
        UnitType reloaded = new UnitType("mod-special"); reloaded.hidden = true;
        UnitProfiles next = new UnitProfiles(store, Seq.with(reloaded));
        assertFalse(next.get(reloaded).banned); assertTrue(next.get(reloaded).defaultBanned);
    }
}
