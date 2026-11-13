package controlgroups;

import arc.Settings;
import arc.math.Mathf;

/** All values are logical UI units, except fractions and explicitly named ticks. */
public final class ModSettings {
    private static final String PREFIX = "control-groups.";
    private final Settings store;

    public boolean vertical = true, reverse, projection = true, collapse;
    public boolean align = true, unassigned, unassignedFirst = true, hover = true;
    public boolean adapt = true, advanced, checkMotion = true;
    public float length = 640f, breadth = 240f, xFraction, yFraction = 0.5f;
    public float absoluteX, absoluteY;
    public float baseSize = 8f, compression = 0.2f, occupancy = 0.8f;
    public float maxProjection = 0f, zeroGap = 6f, groupGap = 4f, tierGap = 4f, typeGap = 4f;
    public float damageTicks = 120f;
    public int revision;

    public ModSettings(Settings store) {
        this.store = store;
        vertical = bool("vertical", vertical);
        reverse = bool("reverse", reverse);
        projection = bool("projection", projection);
        collapse = bool("collapse", collapse);
        align = bool("align", align);
        unassigned = bool("unassigned", unassigned);
        unassignedFirst = bool("unassigned-first", unassignedFirst);
        hover = bool("hover", hover);
        adapt = bool("adapt", adapt);
        advanced = bool("advanced", advanced);
        checkMotion = bool("check-motion", checkMotion);
        length = number("length", length, 160f, 8192f);
        breadth = number("breadth", breadth, 48f, 4096f);
        xFraction = number("x", xFraction, 0f, 1f);
        yFraction = number("y", yFraction, 0f, 1f);
        absoluteX = number("absolute-x", 0f, 0f, 32768f);
        absoluteY = number("absolute-y", 0f, 0f, 32768f);
        baseSize = number("base", baseSize, 1f, 1024f);
        compression = number("compression", compression, 0f, 1f);
        occupancy = number("occupancy", occupancy, 0.01f, 1f);
        maxProjection = number("max-projection", maxProjection, 0f, 2048f);
        zeroGap = number("zero-gap", zeroGap, 0f, 100f);
        groupGap = number("group-gap", groupGap, 0f, 100f);
        tierGap = number("tier-gap", tierGap, 0f, 100f);
        typeGap = number("type-gap", typeGap, 0f, 100f);
        damageTicks = number("damage-ticks", damageTicks, 0f, 3600f);
    }

    public void changed() {
        revision++;
        put("vertical", vertical); put("reverse", reverse); put("projection", projection);
        put("collapse", collapse); put("align", align); put("unassigned", unassigned);
        put("unassigned-first", unassignedFirst); put("hover", hover); put("adapt", adapt);
        put("advanced", advanced); put("check-motion", checkMotion);
        put("length", length); put("breadth", breadth); put("x", xFraction); put("y", yFraction);
        put("absolute-x", absoluteX); put("absolute-y", absoluteY);
        put("base", baseSize); put("compression", compression); put("occupancy", occupancy);
        put("max-projection", maxProjection); put("zero-gap", zeroGap); put("group-gap", groupGap);
        put("tier-gap", tierGap); put("type-gap", typeGap); put("damage-ticks", damageTicks);
    }

    public static float finite(float value, float fallback, float min, float max) {
        return Float.isFinite(value) ? Mathf.clamp(value, min, max) : fallback;
    }

    private boolean bool(String key, boolean fallback) {
        Object value = store.get(PREFIX + key, fallback);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private float number(String key, float fallback, float min, float max) {
        Object value = store.get(PREFIX + key, fallback);
        return value instanceof Number n ? finite(n.floatValue(), fallback, min, max) : fallback;
    }

    private void put(String key, Object value) {
        store.put(PREFIX + key, value);
    }
}
