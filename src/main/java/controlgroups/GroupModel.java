package controlgroups;

import arc.struct.IntMap;
import arc.struct.Seq;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.ai.types.LogicAI;
import mindustry.entities.units.WeaponMount;
import mindustry.game.Team;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.type.weapons.RepairBeamWeapon.HealBeamMount;

import arc.struct.IntSeq;
import java.util.function.IntFunction;

/** The only owner of group membership, type counts and per-unit observations. */
public final class GroupModel {
    public static final int SAVED_GROUPS = 10, UNASSIGNED = 10, GROUPS = 11;
    private static final int[] NO_SHOTS = new int[0];

    public static final class Observation {
        public Unit unit;
        public Activity activity = Activity.IDLE;
        public int mask;
        private UnitProfiles.Profile profile;
        private boolean sampleValid;
        private float health, shield;
        private double damageUntil, firingUntil, repairUntil;
        private int[] shots = NO_SHOTS;
        private WeaponMount[] mounts;
    }

    public static final class Bucket {
        public final UnitProfiles.Profile profile;
        public int count;
        public String countText = "";
        private int pending;

        private Bucket(UnitProfiles.Profile profile) {
            this.profile = profile;
        }
    }

    public static final class Group {
        public final int index;
        public final Seq<Observation> members = new Seq<>();
        public final Seq<Bucket> types = new Seq<>();
        public Activity activity = Activity.IDLE;
        public Observation representative;
        public int compositionRevision;
        private final Bucket[] buckets;

        private Group(int index, UnitProfiles profiles, Seq<mindustry.type.UnitType> types) {
            this.index = index;
            buckets = new Bucket[profiles.capacity()];
            for (var type : types) buckets[type.id] = new Bucket(profiles.get(type));
        }

        public boolean empty() {
            return members.isEmpty();
        }
    }

    public final Group[] groups = new Group[GROUPS];
    private final UnitProfiles profiles;
    private final IntMap<Observation> byId = new IntMap<>();
    private final Seq<Observation> active = new Seq<>(), pool = new Seq<>();
    private final java.util.Comparator<Bucket> typeOrder = (a, b) -> {
        int tier = Integer.compare(b.profile.tier, a.profile.tier);
        if (tier != 0) return tier;
        int order = Integer.compare(a.profile.order, b.profile.order);
        return order != 0 ? order : Integer.compare(a.profile.unit.id, b.profile.unit.id);
    };
    private int profileRevision = -1;
    public int revision, classifiedUnits;
    public long membershipNanos, stateNanos, reconciliations, stateUpdates, sorts;
    public boolean telemetry;

    public GroupModel(UnitProfiles profiles, Seq<mindustry.type.UnitType> types) {
        this.profiles = profiles;
        for (int i = 0; i < GROUPS; i++) groups[i] = new Group(i, profiles, types);
    }

    public int observedUnits() {
        return active.size;
    }

    /** Never mutates saved IDs. Missing network entities may return in a later snapshot. */
    public void reconcile(IntSeq[] saved, IntFunction<Unit> resolve, Seq<Unit> teamUnits,
                          Team team, boolean includeUnassigned) {
        long started = telemetry ? System.nanoTime() : 0;
        for (int i = 0; i < active.size; i++) active.get(i).mask = 0;
        for (Group group : groups) {
            group.members.clear();
            for (Bucket bucket : group.buckets) if (bucket != null) bucket.pending = 0;
        }
        for (int i = 0; i < Math.min(saved.length, SAVED_GROUPS); i++) {
            IntSeq ids = saved[i];
            if (ids == null) continue;
            for (int j = 0; j < ids.size; j++) {
                Unit unit = resolve.apply(ids.items[j]);
                if (available(unit, team) && profiles.allowed(unit.type)) add(unit, i);
            }
        }
        if (includeUnassigned) {
            for (int i = 0; i < teamUnits.size; i++) {
                Unit unit = teamUnits.get(i);
                if (!available(unit, team) || !profiles.allowed(unit.type)) continue;
                if (resolve.apply(unit.id) != unit) continue;
                Observation found = byId.get(unit.id);
                if (found == null || found.unit != unit || found.mask == 0) add(unit, UNASSIGNED);
            }
        }
        for (int i = active.size - 1; i >= 0; i--) {
            Observation observation = active.get(i);
            if (observation.mask == 0) {
                if (byId.get(observation.unit.id) == observation) byId.remove(observation.unit.id);
                observation.unit = null;
                observation.mounts = null;
                observation.profile = null;
                pool.add(observation);
                active.remove(i);
            }
        }
        boolean profileChanged = profileRevision != profiles.revision;
        profileRevision = profiles.revision;
        for (Group group : groups) {
            boolean changed = profileChanged;
            for (Bucket bucket : group.buckets) {
                if (bucket == null) continue;
                if (bucket.count != bucket.pending) {
                    changed = true;
                    bucket.count = bucket.pending;
                    bucket.countText = bucket.count == 0 ? "" : Integer.toString(bucket.count);
                }
            }
            if (changed) {
                group.types.clear();
                for (Bucket bucket : group.buckets) if (bucket != null && bucket.count > 0) group.types.add(bucket);
                group.types.sort(typeOrder);
                group.compositionRevision++;
                revision++;
                if (telemetry) sorts++;
            }
        }
        if (telemetry) { reconciliations++; membershipNanos = System.nanoTime() - started; }
    }

    private void add(Unit unit, int groupIndex) {
        Observation observation = byId.get(unit.id);
        if (observation == null || observation.unit != unit) {
            observation = pool.isEmpty() ? new Observation() : pool.pop();
            observation.unit = unit;
            observation.profile = profiles.get(unit.type);
            observation.sampleValid = false;
            observation.mask = 0;
            observation.activity = Activity.IDLE;
            observation.health = unit.health;
            observation.shield = unit.shield;
            observation.damageUntil = observation.firingUntil = observation.repairUntil = 0;
            initialiseShots(observation);
            byId.put(unit.id, observation);
            active.add(observation);
        }
        int bit = 1 << groupIndex;
        if ((observation.mask & bit) != 0) return;
        observation.mask |= bit;
        Group group = groups[groupIndex];
        group.members.add(observation);
        group.buckets[unit.type.id].pending++;
    }

    public void sample(Team team, ModSettings settings, double now) {
        long started = telemetry ? System.nanoTime() : 0;
        if (telemetry) classifiedUnits = 0;
        for (int i = 0; i < active.size; i++) {
            Observation observation = active.get(i);
            observation.sampleValid = available(observation.unit, team) && profiles.allowed(observation.unit.type);
            if (observation.sampleValid) {
                observation.profile = profiles.get(observation.unit.type);
            }
            if (!observation.sampleValid) {
                observation.activity = Activity.IDLE;
                continue;
            }
            observation.activity = classify(observation, settings, now);
            if (telemetry) classifiedUnits++;
        }
        for (Group group : groups) {
            Observation best = group.representative;
            if (best == null || (best.mask & (1 << group.index)) == 0 || !best.sampleValid) best = null;
            for (int i = 0; i < group.members.size; i++) {
                Observation candidate = group.members.get(i);
                if (!candidate.sampleValid) continue;
                if (best == null || candidate.activity.priority > best.activity.priority ||
                    (candidate.activity.priority == best.activity.priority &&
                        candidate.profile.tier > best.profile.tier)) best = candidate;
            }
            group.representative = best;
            group.activity = best == null ? Activity.IDLE : best.activity;
        }
        if (telemetry) { stateUpdates++; stateNanos = System.nanoTime() - started; }
    }

    public void damage(Unit unit, double now, float ticks) {
        Observation observation = byId.get(unit.id);
        if (observation != null && observation.unit == unit) observation.damageUntil = now + ticks / 60.0;
    }

    private Activity classify(Observation observation, ModSettings settings, double now) {
        Unit unit = observation.unit;
        if (unit.health < observation.health - 0.001f || unit.shield < observation.shield - 0.001f || unit.hitTime > 0f) {
            observation.damageUntil = now + settings.damageTicks / 60.0;
        }
        observation.health = unit.health;
        observation.shield = unit.shield;
        boolean continuous = false, fired = false;
        if (observation.mounts != unit.mounts) initialiseShots(observation);
        for (int i = 0; i < unit.mounts.length; i++) {
            WeaponMount mount = unit.mounts[i];
            boolean shot = observation.shots[i] != mount.totalShots;
            observation.shots[i] = mount.totalShots;
            boolean healing = mount instanceof HealBeamMount || (mount.weapon.bullet != null &&
                (mount.weapon.bullet.healPercent > 0f || mount.weapon.bullet.healAmount > 0f) &&
                mount.target != null && mount.target.team() == unit.team);
            if (healing) {
                if (mount.target instanceof Healthc health && health.isValid() && health.damaged() &&
                    mount.target.team() == unit.team && mount.shoot &&
                    (mount instanceof HealBeamMount heal ? heal.strength > 0.01f :
                        shot || (mount.bullet != null && mount.bullet.isAdded()))) observation.repairUntil = now + UpdatePolicy.ACTION_HOLD_SECONDS;
            } else if (!mount.weapon.noAttack) {
                if (shot) fired = true;
                if (mount.bullet != null && mount.bullet.isAdded() && mount.shoot) continuous = true;
            }
        }
        if (fired) observation.firingUntil = now + UpdatePolicy.ACTION_HOLD_SECONDS;
        boolean attack = continuous || now < observation.firingUntil;
        boolean damage = now < observation.damageUntil;
        if (damage) return attack ? Activity.COMBAT : Activity.DAMAGED;
        if (attack) return Activity.ATTACK;
        CommandAI command = unit.controller() instanceof CommandAI ai ? ai : null;
        if (command != null && command.attackTarget != null && command.attackTarget.team() != unit.team) return Activity.PURSUE;
        if (now < observation.repairUntil) return Activity.REPAIR;
        if (unit.activelyBuilding()) return Activity.BUILD;
        if (unit.mining() && unit.mineTile != null && unit.within(unit.mineTile, unit.type.mineRange)) return Activity.MINE;
        boolean moving = unit.vel.len2() >= profiles.movementThreshold * profiles.movementThreshold;
        if (command != null && UnitStance.patrol != null && command.hasStance(UnitStance.patrol) && command.targetPos != null) {
            return settings.checkMotion && !moving ? Activity.PATROL_BLOCKED : Activity.PATROL;
        }
        if (unit.drownTime >= 0.25f) return Activity.DROWNING;
        if ((command != null && command.targetPos != null) || moving) {
            return settings.checkMotion && !moving ? Activity.BLOCKED : Activity.MOVE;
        }
        if (unit.controller() instanceof LogicAI) return Activity.LOGIC;
        return Activity.IDLE;
    }

    private static void initialiseShots(Observation observation) {
        Unit unit = observation.unit;
        observation.mounts = unit.mounts;
        if (observation.shots.length != unit.mounts.length) observation.shots = new int[unit.mounts.length];
        for (int i = 0; i < unit.mounts.length; i++) observation.shots[i] = unit.mounts[i].totalShots;
    }

    public static boolean available(Unit unit, Team team) {
        return unit != null && unit.type != null && unit.team == team && unit.isValid();
    }

    public void clear() {
        byId.clear();
        for (int i = 0; i < active.size; i++) {
            Observation observation = active.get(i);
            observation.unit = null; observation.mounts = null; observation.profile = null;
        }
        active.clear();
        pool.clear();
        for (Group group : groups) {
            group.members.clear(); group.types.clear(); group.representative = null; group.activity = Activity.IDLE;
            for (Bucket bucket : group.buckets) if (bucket != null) bucket.count = bucket.pending = 0;
            group.compositionRevision++;
        }
        revision++;
        profileRevision = -1;
    }
}
