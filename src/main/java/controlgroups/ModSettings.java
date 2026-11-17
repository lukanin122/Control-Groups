package controlgroups;

import arc.Settings;
import arc.math.Mathf;

/** Persistent logical UI values. Derived active collapse is never stored as a third preference. */
public final class ModSettings {
    public enum SpaceMode { LIMITED, EQUAL, PROPORTIONAL }
    public enum FreeSpaceMode { FOLLOW, LIMITED, RESIDUAL }
    private static final String PREFIX = "control-groups.";
    private final Settings store;

    public boolean vertical = true, reverse, projection = true;
    public boolean collapseCommand = true, collapseBuild, collapse = true;
    public boolean align = true, unassigned, unassignedFirst = true, hover = true;
    public boolean adapt = true, advanced, checkMotion = true, showBuild = true;
    public boolean iconInfluence = true, minTrackingSquare = true, internalProjection, fullIcons = true, hideSingles;
    public boolean manualLargest;
    public boolean editorSchematic = true, editorExpanded = true;
    public SpaceMode spaceMode = SpaceMode.PROPORTIONAL;
    public FreeSpaceMode freeSpaceMode = FreeSpaceMode.LIMITED;
    public float length = 640f, breadth = 240f, xFraction, yFraction = 0.5f;
    public float absoluteX, absoluteY;
    public float baseSize = 8f, compression = 0.2f, occupancy = 0.8f;
    public float zeroGap = 6f, groupGap = 4f, tierGap = 4f, typeGap = 4f, counterGap = 2f;
    public float iconSize = 36f, iconCoefficient = 0.2f, freeMinimum = 32f, manualLargestSize = 8f;
    public float damageTicks = 120f;
    public int revision;
    private boolean commandContext = true;
    private boolean manualSizeChosen;

    public ModSettings(Settings store) {
        this.store = store;
        vertical = bool("vertical", vertical); reverse = bool("reverse", reverse);
        projection = bool("projection", projection);
        // The former single switch migrates only to command mode. Building has an independent default.
        collapseCommand = bool("collapse-command", bool("collapse", true));
        collapseBuild = bool("collapse-build", false);
        collapse = collapseCommand;
        align = bool("align", align); unassigned = bool("unassigned", unassigned);
        unassignedFirst = bool("unassigned-first", unassignedFirst); hover = bool("hover", hover);
        adapt = bool("adapt", adapt); advanced = bool("advanced", advanced);
        checkMotion = bool("check-motion", checkMotion); showBuild = bool("show-build", showBuild);
        iconInfluence = bool("icon-influence", iconInfluence);
        minTrackingSquare = bool("tracking-square", minTrackingSquare);
        internalProjection = bool("internal-projection", internalProjection);
        fullIcons = bool("full-icons", fullIcons); hideSingles = bool("hide-singles", hideSingles);
        manualLargest = bool("manual-largest", manualLargest);
        manualSizeChosen = bool("manual-size-chosen", manualLargest);
        editorSchematic = bool("editor-schematic", editorSchematic);
        editorExpanded = bool("editor-expanded", editorExpanded);
        spaceMode = enumValue("space-mode", SpaceMode.values(), spaceMode);
        freeSpaceMode = enumValue("free-mode", FreeSpaceMode.values(), freeSpaceMode);
        length = number("length", length, 160, 8192); breadth = number("breadth", breadth, 48, 4096);
        xFraction = number("x", xFraction, 0, 1); yFraction = number("y", yFraction, 0, 1);
        absoluteX = number("absolute-x", 0, 0, 32768); absoluteY = number("absolute-y", 0, 0, 32768);
        baseSize = number("base", baseSize, 0.01f, 4096);
        compression = number("compression", compression, 0, 1);
        occupancy = number("occupancy", occupancy, 0.01f, 1);
        zeroGap = number("zero-gap", zeroGap, 0, 1000); groupGap = number("group-gap", groupGap, 0, 1000);
        tierGap = number("tier-gap", tierGap, 0, 1000); typeGap = number("type-gap", typeGap, 0, 1000);
        counterGap = number("counter-gap", counterGap, 0, 1000);
        iconSize = number("icon-size", iconSize, 1, 1024);
        iconCoefficient = number("icon-coefficient", iconCoefficient, 0, 1);
        freeMinimum = number("free-minimum", freeMinimum, 0, Float.MAX_VALUE);
        manualLargestSize = number("manual-size", manualLargestSize, 0.01f, Float.MAX_VALUE);
        damageTicks = number("damage-ticks", damageTicks, 0, 3600);
    }

    public void initializeLargest(float automatic) {
        if (!manualSizeChosen) manualLargestSize = Math.max(0.01f, automatic);
    }

    public void setManualLargestSize(float value) { manualLargestSize = value; manualSizeChosen = true; }

    public void useMode(boolean command) {
        commandContext = command;
        collapse = command ? collapseCommand : collapseBuild;
    }

    public boolean commandContext() { return commandContext; }

    /** Presentation preferences do not invalidate game data or HUD geometry. */
    public void setEditorSchematic(boolean enabled) {
        editorSchematic = enabled; put("editor-schematic", enabled);
    }

    public void setEditorExpanded(boolean enabled) {
        editorExpanded = enabled; put("editor-expanded", enabled);
    }

    public void setCollapse(boolean enabled) {
        if (commandContext) collapseCommand = enabled;
        else collapseBuild = enabled;
        collapse = enabled;
    }

    public void changed() {
        revision++;
        if (manualLargest) manualSizeChosen = true;
        put("vertical", vertical); put("reverse", reverse); put("projection", projection);
        put("collapse-command", collapseCommand); put("collapse-build", collapseBuild);
        put("align", align); put("unassigned", unassigned); put("unassigned-first", unassignedFirst);
        put("hover", hover); put("adapt", adapt); put("advanced", advanced); put("check-motion", checkMotion);
        put("show-build", showBuild); put("icon-influence", iconInfluence); put("tracking-square", minTrackingSquare);
        put("internal-projection", internalProjection); put("full-icons", fullIcons); put("hide-singles", hideSingles);
        put("manual-largest", manualLargest); put("manual-size", manualLargestSize);
        put("manual-size-chosen", manualSizeChosen);
        put("editor-schematic", editorSchematic); put("editor-expanded", editorExpanded);
        put("space-mode", spaceMode.name()); put("free-mode", freeSpaceMode.name());
        put("length", length); put("breadth", breadth); put("x", xFraction); put("y", yFraction);
        put("absolute-x", absoluteX); put("absolute-y", absoluteY);
        put("base", baseSize); put("compression", compression); put("occupancy", occupancy);
        put("zero-gap", zeroGap); put("group-gap", groupGap); put("tier-gap", tierGap); put("type-gap", typeGap);
        put("counter-gap", counterGap); put("icon-size", iconSize); put("icon-coefficient", iconCoefficient);
        put("free-minimum", freeMinimum); put("damage-ticks", damageTicks);
    }

    public static float finite(float value, float fallback, float min, float max) {
        return Float.isFinite(value) ? Mathf.clamp(value, min, max) : fallback;
    }

    private <E extends Enum<E>> E enumValue(String key, E[] values, E fallback) {
        Object saved = store.get(PREFIX + key, fallback.name());
        for (E value : values) if (value.name().equals(saved)) return value;
        return fallback;
    }

    private boolean bool(String key, boolean fallback) {
        Object value = store.get(PREFIX + key, fallback);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private float number(String key, float fallback, float min, float max) {
        Object value = store.get(PREFIX + key, fallback);
        return value instanceof Number n ? finite(n.floatValue(), fallback, min, max) : fallback;
    }

    private void put(String key, Object value) { store.put(PREFIX + key, value); }
}
