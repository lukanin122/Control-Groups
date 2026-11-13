package controlgroups;

import arc.math.geom.Vec2;
import mindustry.ai.UnitStance;
import mindustry.ai.types.LogicAI;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.units.WeaponMount;
import mindustry.game.Team;
import mindustry.type.Weapon;
import org.junit.Test;

import static org.junit.Assert.*;

public class GroupModelTest {
    @Test
    public void overlappingAndDuplicateSavedIdsAreCountedOncePerGroup() {
        TestWorld w = new TestWorld(2);
        w.add(1, 0); w.add(2, 1); w.add(3, 0);
        w.group(0, 1, 1, 2, 999); w.group(3, 1);
        w.reconcile(true); w.sample(1);
        assertEquals(3, w.model.observedUnits());
        assertEquals(2, w.model.groups[0].members.size);
        assertEquals(1, w.model.groups[3].members.size);
        assertEquals(1, w.model.groups[10].members.size);
        assertEquals(3, w.model.classifiedUnits);
        assertEquals(4, w.saved[0].size); // Missing/duplicate IDs remain owned by the game.
        w.group(0, 2); w.group(3, 1, 3); w.reconcile(true);
        assertTrue(w.model.groups[10].empty());
        w.group(3); w.reconcile(true);
        assertEquals(2, w.model.groups[10].members.size);
    }

    @Test
    public void deadMissingForeignAndReplacedEntitiesDoNotLeaveStaleObservations() {
        TestWorld w = new TestWorld(1);
        var alive = w.add(1, 0);
        var dead = w.add(2, 0); dead.valid = false;
        var enemy = w.add(3, 0); enemy.team = Team.crux;
        w.group(0, 1, 2, 3, 4); w.reconcile(false); w.sample(1);
        assertEquals(1, w.model.observedUnits());
        alive.health -= 10; w.sample(2);
        assertEquals(Activity.DAMAGED, w.model.groups[0].activity);
        var replacement = w.add(1, 0);
        w.reconcile(false); w.sample(2.1);
        assertEquals(1, w.model.observedUnits());
        assertSame(replacement, w.model.groups[0].representative.unit);
        assertEquals(Activity.IDLE, w.model.groups[0].activity);
        replacement.valid = false; w.sample(2.2);
        assertNull(w.model.groups[0].representative);
        w.reconcile(false);
        assertTrue(w.model.groups[0].empty());
        w.model.clear();
        assertEquals(0, w.model.observedUnits());
        assertTrue(w.model.groups[0].types.isEmpty());
    }

    @Test
    public void representativePrioritisesActivityThenTierAndRetainsTies() {
        TestWorld w = new TestWorld(2);
        var dagger = w.add(1, 0); var reign = w.add(2, 1); var otherReign = w.add(3, 1);
        w.group(0, 1, 2, 3); w.reconcile(false); w.sample(1);
        assertSame(reign, w.model.groups[0].representative.unit);
        w.group(0, 3, 1, 2); w.reconcile(false); w.sample(1.1);
        assertSame(reign, w.model.groups[0].representative.unit);
        dagger.health -= 1; w.sample(1.2);
        assertSame(dagger, w.model.groups[0].representative.unit);
        w.sample(10);
        assertSame(otherReign, w.model.groups[0].representative.unit);
    }

    @Test
    public void observedFiringAndDamageCombineOnlyOnTheSameUnit() {
        TestWorld w = new TestWorld(1);
        var a = w.add(1, 0); var b = w.add(2, 0);
        Weapon weapon = new Weapon("test"); weapon.bullet = new BasicBulletType();
        WeaponMount mount = new WeaponMount(weapon);
        a.mounts = new WeaponMount[]{mount};
        w.group(0, 1, 2); w.reconcile(false); w.sample(1);
        mount.totalShots++; b.health -= 1; w.sample(1.1);
        assertEquals(Activity.DAMAGED, w.model.groups[0].activity);
        a.health -= 1; w.sample(1.2);
        assertEquals(Activity.COMBAT, w.model.groups[0].activity);
        w.sample(8);
        assertEquals(Activity.IDLE, w.model.groups[0].activity);
    }

    @Test
    public void movementPatrolDrowningAndLogicRespectTheirPriorities() {
        TestWorld w = new TestWorld(1); var unit = w.add(1, 0);
        w.group(0, 1); w.reconcile(false);
        unit.command().targetPos = new Vec2(100, 100);
        w.sample(1); assertEquals(Activity.BLOCKED, w.model.groups[0].activity);
        unit.vel.set(1, 0); w.sample(1.1); assertEquals(Activity.MOVE, w.model.groups[0].activity);
        unit.drownTime = 0.4f; w.sample(1.2); assertEquals(Activity.DROWNING, w.model.groups[0].activity);
        UnitStance.patrol = new UnitStance("test-patrol", "refresh", null);
        unit.command().stances.set(UnitStance.patrol.id);
        w.sample(1.3); assertEquals(Activity.PATROL, w.model.groups[0].activity);
        unit.vel.setZero(); w.sample(1.4); assertEquals(Activity.PATROL_BLOCKED, w.model.groups[0].activity);
        unit.building = true; w.sample(1.5); assertEquals(Activity.BUILD, w.model.groups[0].activity);
        unit.building = false; unit.drownTime = 0; unit.controller(new LogicAI());
        w.sample(1.6); assertEquals(Activity.LOGIC, w.model.groups[0].activity);
    }

    @Test
    public void unchangedSnapshotsAndDynamicStatesDoNotRebuildComposition() {
        TestWorld w = new TestWorld(3);
        for (int i = 1; i <= 1000; i++) w.add(i, i % 3);
        for (int g = 0; g < 10; g++) {
            w.group(g);
            for (int i = 1; i <= 1000; i++) w.saved[g].add(i);
        }
        w.reconcile(false); int revision = w.model.revision; long sorts = w.model.sorts;
        for (int i = 0; i < 20; i++) { w.reconcile(false); w.sample(i); }
        assertEquals(revision, w.model.revision);
        assertEquals(sorts, w.model.sorts);
        assertEquals(1000, w.model.classifiedUnits);
        assertEquals(1000, w.model.observedUnits());
    }

    @Test
    public void weaponsThatCanAlsoHealStillReportEnemyFire() {
        TestWorld w = new TestWorld(1); var unit = w.add(1, 0);
        Weapon weapon = new Weapon("hybrid");
        weapon.bullet = new BasicBulletType(); weapon.bullet.healPercent = 5;
        WeaponMount mount = new WeaponMount(weapon); unit.mounts = new WeaponMount[]{mount};
        w.group(0, 1); w.reconcile(false); w.sample(1);
        mount.totalShots++; w.sample(1.1);
        assertEquals(Activity.ATTACK, w.model.groups[0].activity);
        var ally = w.add(2, 0); ally.health = 50;
        mount.target = ally; mount.shoot = true; mount.totalShots++;
        w.sample(2);
        assertEquals(Activity.REPAIR, w.model.groups[0].activity);
        // Switching targets must not reinterpret historical shots as a new attack.
        mount.target = null; w.sample(3);
        assertEquals(Activity.IDLE, w.model.groups[0].activity);
    }

    @Test
    public void unassignedIgnoresAStaleEntityWithAReusedId() {
        TestWorld w = new TestWorld(1); w.add(1, 0);
        var replacement = w.add(1, 0);
        w.reconcile(true); w.sample(1);
        assertEquals(1, w.model.observedUnits());
        assertEquals(1, w.model.groups[10].members.size);
        assertSame(replacement, w.model.groups[10].representative.unit);
    }

    @Test
    public void replacingWeaponMountsDoesNotReportHistoricalCounterChangesAsFire() {
        TestWorld w = new TestWorld(1); var unit = w.add(1, 0);
        Weapon weapon = new Weapon("test"); weapon.bullet = new BasicBulletType();
        WeaponMount old = new WeaponMount(weapon); old.totalShots = 200;
        unit.mounts = new WeaponMount[]{old};
        w.group(0, 1); w.reconcile(false); w.sample(1);
        unit.mounts = new WeaponMount[]{new WeaponMount(weapon)};
        w.sample(2);
        assertEquals(Activity.IDLE, w.model.groups[0].activity);
    }
}
