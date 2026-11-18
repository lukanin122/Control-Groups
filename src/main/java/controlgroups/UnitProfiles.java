package controlgroups;

import arc.Settings;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;

/** Session IDs are array indices only. Persistent keys always use namespaced content names. */
public final class UnitProfiles {
    private static final String PREFIX = "control-groups.unit.";
    private static final String[][] LINES = {
        {"dagger", "mace", "fortress", "scepter", "reign"},
        {"nova", "pulsar", "quasar", "vela", "corvus"},
        {"crawler", "atrax", "spiroct", "arkyid", "toxopid"},
        {"flare", "horizon", "zenith", "antumbra", "eclipse"},
        {"mono", "poly", "mega", "quad", "oct"},
        {"risso", "minke", "bryde", "sei", "omura"},
        {"retusa", "oxynoe", "cyerce", "aegires", "navanax"},
        {"stell", "locus", "precept", "vanquish", "conquer"},
        {"merui", "cleroi", "anthicus", "tecta", "collaris"},
        {"elude", "avert", "obviate", "quell", "disrupt"}
    };
    private static final int[] TYPES = {10, 20, 30, 40, 50, 60, 70, 11, 31, 41};
    private static final String[] COLORS = {
        "FFA665", "8EFA9D", "BF92F9", "FFA665", "8EFA9D", "FFA665", "8EFA9D",
        "FF9966", "99CCFF", "B58AF0"
    };

    public static final class Profile {
        public final UnitType unit;
        public final Color color = new Color();
        public int tier, order;
        public float resize = 1f;
        public boolean banned;
        public int defaultTier, defaultOrder;
        public final Color defaultColor = new Color();
        public boolean defaultBanned;
        public TextureRegion uiIcon, fullIcon;
        public double projectionRadius, advancedProjectionRadius, fallbackRadiusRatio;
        final ProjectionPlan projectionPlan;

        private Profile(UnitType unit, ProjectionBounds bounds) {
            this.unit = unit;
            uiIcon = bounds.trim(unit.uiIcon); fullIcon = bounds.trim(unit.fullIcon);
            projectionRadius = bounds.radius(unit, false);
            advancedProjectionRadius = bounds.radius(unit, true);
            fallbackRadiusRatio = bounds.fallbackRadius(unit);
            projectionPlan = new ProjectionPlan(unit);
        }
    }

    private final Settings store;
    private final Profile[] byId;
    // Membership can contain 100,000 IDs. Avoid a dependent Profile load for every repeated ban check.
    private final boolean[] allowedById;
    public final Seq<Profile> all = new Seq<>();
    public float movementThreshold;
    public float largestHitSize;
    public int revision;

    public UnitProfiles(Settings store, Seq<UnitType> types) {
        this(store, types, new Seq<>());
    }

    public UnitProfiles(Settings store, Seq<UnitType> types, Seq<Block> blocks) {
        this.store = store;
        int capacity = 0;
        for (UnitType type : types) capacity = Math.max(capacity, type.id + 1);
        byId = new Profile[capacity];
        allowedById = new boolean[capacity];
        ProjectionBounds bounds = new ProjectionBounds();
        for (UnitType type : types) {
            Profile profile = new Profile(type, bounds);
            defaults(profile);
            profile.defaultTier = profile.tier; profile.defaultOrder = profile.order;
            profile.defaultColor.set(profile.color);
            profile.defaultBanned = type.hidden || type.internal || !type.playerControllable;
            for (Block block : blocks) if (block instanceof CoreBlock core && core.unitType == type) {
                profile.defaultBanned = true; break;
            }
            String key = PREFIX + type.name + ".";
            Object savedColor = store.get(key + "color", "");
            String color = savedColor instanceof String text ? text : "";
            if (validColor(color)) profile.color.set(Color.valueOf(stripHash(color)));
            profile.tier = clampIndex((int)number(key + "tier", profile.tier));
            profile.order = clampIndex((int)number(key + "order", profile.order));
            profile.resize = ModSettings.finite(number(key + "resize", 1f), 1f, 0.01f, 20f);
            Object ban = store.get(key + "ban", profile.defaultBanned);
            profile.banned = ban instanceof Boolean flag ? flag : profile.defaultBanned;
            byId[type.id] = profile;
            allowedById[type.id] = !profile.banned;
            all.add(profile);
        }
        all.sort((a, b) -> Integer.compare(a.unit.id, b.unit.id));
        refreshExtents();
    }

    public int capacity() {
        return byId.length;
    }

    public Profile get(UnitType type) {
        return type != null && type.id >= 0 && type.id < byId.length ? byId[type.id] : null;
    }

    public void save(Profile profile) {
        String key = PREFIX + profile.unit.name + ".";
        store.put(key + "color", profile.color.toString().substring(0, 6));
        store.put(key + "tier", profile.tier);
        store.put(key + "order", profile.order);
        store.put(key + "resize", profile.resize);
        store.put(key + "ban", profile.banned);
        allowedById[profile.unit.id] = !profile.banned;
        refreshExtents();
        revision++;
    }

    public boolean allowed(UnitType type) {
        return type != null && type.id >= 0 && type.id < allowedById.length && allowedById[type.id];
    }

    /** Metadata only, invoked on profile edits rather than scanning live units. */
    private void refreshExtents() {
        float slowest = Float.MAX_VALUE, largest = 0;
        for (int i = 0; i < all.size; i++) {
            Profile profile = all.get(i);
            if (profile.banned) continue;
            if (Float.isFinite(profile.unit.hitSize)) largest = Math.max(largest, profile.unit.hitSize);
            if (profile.unit.speed > 0 && Float.isFinite(profile.unit.speed)) slowest = Math.min(slowest, profile.unit.speed);
        }
        largestHitSize = largest;
        movementThreshold = slowest == Float.MAX_VALUE ? 0.01f : Math.max(0.001f, slowest / 3f);
    }

    public static boolean validColor(String text) {
        if (text == null) return false;
        String value = stripHash(text);
        if (value.length() != 6) return false;
        for (int i = 0; i < value.length(); i++) if (Character.digit(value.charAt(i), 16) < 0) return false;
        return true;
    }

    public static int clampIndex(int value) {
        return Math.max(1, Math.min(1024, value));
    }

    private static String stripHash(String value) {
        return value.startsWith("#") ? value.substring(1) : value;
    }

    private float number(String key, float fallback) {
        Object value = store.get(key, fallback);
        return value instanceof Number n && Float.isFinite(n.floatValue()) ? n.floatValue() : fallback;
    }

    private static void defaults(Profile profile) {
        profile.tier = 10;
        profile.order = Math.min(1024, 100 + profile.unit.id);
        profile.color.set(Color.lightGray);
        for (int row = 0; row < LINES.length; row++) {
            for (int column = 0; column < LINES[row].length; column++) {
                if (profile.unit.name.equals(LINES[row][column])) {
                    profile.tier = (column + 1) * 10;
                    profile.order = TYPES[row];
                    profile.color.set(Color.valueOf(COLORS[row]));
                    return;
                }
            }
        }
    }
}
