package controlgroups;

import arc.Settings;
import arc.graphics.Color;
import arc.struct.Seq;
import mindustry.type.UnitType;

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

        private Profile(UnitType unit) {
            this.unit = unit;
        }
    }

    private final Settings store;
    private final Profile[] byId;
    public final Seq<Profile> controllable = new Seq<>();
    public final float movementThreshold;
    public final float largestHitSize;
    public int revision;

    public UnitProfiles(Settings store, Seq<UnitType> types) {
        this.store = store;
        int capacity = 0;
        for (UnitType type : types) capacity = Math.max(capacity, type.id + 1);
        byId = new Profile[capacity];
        float slowest = Float.MAX_VALUE, largest = 1f;
        for (UnitType type : types) {
            Profile profile = new Profile(type);
            defaults(profile);
            String key = PREFIX + type.name + ".";
            Object savedColor = store.get(key + "color", "");
            String color = savedColor instanceof String text ? text : "";
            if (validColor(color)) profile.color.set(Color.valueOf(stripHash(color)));
            profile.tier = clampIndex((int)number(key + "tier", profile.tier));
            profile.order = clampIndex((int)number(key + "order", profile.order));
            profile.resize = ModSettings.finite(number(key + "resize", 1f), 1f, 0.01f, 20f);
            byId[type.id] = profile;
            if (type.playerControllable) {
                controllable.add(profile);
                if (type.speed > 0f) slowest = Math.min(slowest, type.speed);
                if (Float.isFinite(type.hitSize)) largest = Math.max(largest, type.hitSize);
            }
        }
        movementThreshold = slowest == Float.MAX_VALUE ? 0.01f : Math.max(0.001f, slowest / 3f);
        largestHitSize = largest;
        controllable.sort((a, b) -> Integer.compare(a.unit.id, b.unit.id));
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
        revision++;
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
