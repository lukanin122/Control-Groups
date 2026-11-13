package controlgroups;

import arc.Settings;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.ai.types.CommandAI;
import mindustry.core.ContentLoader;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.gen.UnitEntity;
import mindustry.type.UnitType;

/** Small client-data fixture, deliberately not a running simulation or renderer. */
final class TestWorld {
    final Settings store = new Settings();
    final ModSettings settings = new ModSettings(store);
    final Seq<UnitType> types;
    final UnitProfiles profiles;
    final GroupModel model;
    final IntSeq[] saved = new IntSeq[10];
    final IntMap<Unit> units = new IntMap<>();
    final Seq<Unit> teamUnits = new Seq<>();
    final java.util.function.IntFunction<Unit> resolve = units::get;

    TestWorld(int typeCount) {
        Vars.content = new ContentLoader();
        types = new Seq<>();
        for (int i = 0; i < typeCount; i++) {
            UnitType type = new UnitType(i == 0 ? "dagger" : i == 1 ? "reign" : "test-unit-" + i);
            type.hitSize = 8 + i * 2;
            type.speed = 0.6f + i * 0.1f;
            type.playerControllable = true;
            types.add(type);
        }
        profiles = new UnitProfiles(store, types);
        model = new GroupModel(profiles, types);
    }

    FakeUnit add(int id, int type) {
        FakeUnit unit = new FakeUnit();
        unit.id = id;
        unit.type = types.get(type);
        unit.team = Team.sharded;
        unit.health = unit.maxHealth = 100f;
        unit.hitSize = unit.type.hitSize;
        unit.controller(new CommandAI());
        units.put(id, unit); teamUnits.add(unit);
        return unit;
    }

    void group(int index, int... ids) {
        saved[index] = new IntSeq(ids);
    }

    void reconcile(boolean unassigned) {
        model.reconcile(saved, resolve, teamUnits, Team.sharded, unassigned);
    }

    void sample(double time) {
        model.sample(Team.sharded, settings, time);
    }

    static final class FakeUnit extends UnitEntity {
        boolean valid = true, building, mining;

        @Override public boolean isValid() { return valid && !dead; }
        @Override public boolean activelyBuilding() { return building; }
        @Override public boolean mining() { return mining; }
    }
}
