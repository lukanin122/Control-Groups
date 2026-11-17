package controlgroups;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Collapser;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.util.Scaling;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import static controlgroups.UiControls.*;

/** A single editor for the live HUD. Section state is presentation, never another game model. */
final class HudEditor extends Table {
    private static final float MIN_PANEL_WIDTH = 480f;
    private final float sectionIndent, toolbarButtonSize;
    private final TextureRegionDrawable rightArrow, downArrow;
    private final GroupsHud hud;
    private final Table toolbar = new Table();
    private ImageButton eye, collapse, direction, schematic, expand;
    private final ScrollPane settingsScroll;
    private final Label stats = new Label("");
    private final Collapser telemetry;
    private int toolbarColumns;
    private float statsTimer;

    HudEditor(GroupsHud hud) {
        this.hud = hud;
        rightArrow = disclosure("rightOpen"); downArrow = disclosure("downOpen");
        sectionIndent = downArrow.getMinWidth() / Scl.scl();
        float buttonSize = BUTTON_SIZE;
        for (String icon : new String[]{"move", "refresh", "flipX", "flipY", "list", "eye", "eyeOff", "cancel", "left", "right", "up", "down", "grid", "bookOpen"})
            buttonSize = Math.max(buttonSize, GroupsHud.drawable(icon).imageSize() + 2 * BUTTON_INSET);
        toolbarButtonSize = (float)Math.ceil(buttonSize);
        toolbar.left();
        toolbar.touchable = Touchable.childrenOnly;
        name = "control-groups-editor";
        background(Tex.pane);
        margin(4); top().left();
        Table content = new Table();
        content.top().left();
        var s = hud.settings;
        Table main = new Table(), advanced = new Table(), diagnostics = new Table();
        section(content, "main-settings", main, true, true);

        Table allocation = new Table();
        section(main, "space-allocation", allocation, true, false);
        for (var mode : ModSettings.SpaceMode.values()) {
            String key = "space-" + mode.name().toLowerCase(java.util.Locale.ROOT);
            flag(allocation, key, () -> s.spaceMode == mode, value -> { if (value) s.spaceMode = mode; });
        }
        Table free = new Table();
        section(main, "free-group-settings", free, false, false);
        flag(free, "unassigned", () -> s.unassigned, value -> s.unassigned = value);
        flag(free, "unassigned-first", () -> s.unassignedFirst, value -> s.unassignedFirst = value);
        Table freeWidth = new Table();
        section(free, "free-width", freeWidth, false, false);
        for (var mode : ModSettings.FreeSpaceMode.values()) {
            String key = "free-" + mode.name().toLowerCase(java.util.Locale.ROOT);
            if (mode != ModSettings.FreeSpaceMode.RESIDUAL) {
                flag(freeWidth, key, () -> s.freeSpaceMode == mode, value -> { if (value) s.freeSpaceMode = mode; });
            } else {
                var field = numeric("free-minimum", () -> s.freeMinimum, () -> 32, 0, Float.MAX_VALUE,
                    value -> s.freeMinimum = (float)value).enabled(() -> s.freeSpaceMode == mode);
                UiControls.row(hud, freeWidth, key, combination(key, () -> s.freeSpaceMode == mode,
                    value -> { if (value) { s.freeSpaceMode = mode; hud.changed(); } }, field));
            }
        }
        Table icons = new Table();
        section(main, "group-icon-settings", icons, false, false);
        numberRow(icons, "icon-size", () -> s.iconSize, () -> 36, 1, 1024, value -> s.iconSize = (float)value);
        flag(icons, "icon-influence", () -> s.iconInfluence, value -> s.iconInfluence = value);
        numberRow(icons, "icon-coefficient", () -> s.iconCoefficient * 100, () -> 20, 0, 100,
            value -> s.iconCoefficient = (float)value / 100);

        Table tracking = new Table();
        section(main, "tracking-settings", tracking, false, false);
        CheckBox square = flag(tracking, "tracking-square", () -> s.minTrackingSquare, value -> s.minTrackingSquare = value);
        square.update(() -> { square.setDisabled(s.internalProjection); square.setChecked(s.minTrackingSquare && !s.internalProjection); });
        flag(tracking, "internal-projection", () -> s.internalProjection, value -> s.internalProjection = value);
        flag(tracking, "advanced", () -> s.advanced, value -> s.advanced = value);

        Table gaps = new Table();
        section(main, "gap-settings", gaps, false, false);
        numberRow(gaps, "zero-gap", () -> s.zeroGap, () -> 6, 0, 1000, value -> s.zeroGap = (float)value);
        numberRow(gaps, "group-gap", () -> s.groupGap, () -> 4, 0, 1000, value -> s.groupGap = (float)value);
        numberRow(gaps, "tier-gap", () -> s.tierGap, () -> 4, 0, 1000, value -> s.tierGap = (float)value);
        numberRow(gaps, "type-gap", () -> s.typeGap, () -> 4, 0, 1000, value -> s.typeGap = (float)value);
        numberRow(gaps, "counter-gap", () -> s.counterGap, () -> 2, 0, 1000, value -> s.counterGap = (float)value);
        main.add().height(6).row();
        flag(main, "show-build", () -> s.showBuild, value -> s.showBuild = value);
        flag(main, "full-icons", () -> s.fullIcons, value -> s.fullIcons = value);
        flag(main, "hide-singles", () -> s.hideSingles, value -> s.hideSingles = value);
        flag(main, "align", () -> s.align, value -> s.align = value);
        flag(main, "hover", () -> s.hover, value -> s.hover = value);
        flag(main, "motion", () -> s.checkMotion, value -> s.checkMotion = value);

        section(content, "advanced-settings", advanced, false, true);
        numberRow(advanced, "damage-ticks", () -> s.damageTicks, () -> 120, 0, 3600, value -> s.damageTicks = (float)value);
        Table projection = new Table();
        section(advanced, "projection-settings", projection, false, false);
        flag(projection, "adapt", () -> s.adapt, value -> s.adapt = value);
        var largest = numeric("manual-size", () -> s.manualLargestSize,
            this::automaticLargest, .01, Float.MAX_VALUE, value -> s.setManualLargestSize((float)value)).enabled(() -> s.manualLargest);
        UiControls.row(hud, projection, "manual-largest", combination("manual-largest", () -> s.manualLargest,
            value -> { s.manualLargest = value; hud.changed(); }, largest));
        numberRow(projection, "base", () -> s.baseSize, () -> 8, .01, 4096, value -> s.baseSize = (float)value);
        numberRow(projection, "compression", () -> s.compression * 100, () -> 20, 0, 100,
            value -> s.compression = (float)value / 100);
        numberRow(projection, "occupancy", () -> s.occupancy * 100, () -> 80, 0, 100,
            value -> s.occupancy = (float)value / 100);

        telemetry = section(content, "telemetry", diagnostics, false, true);
        stats.setWrap(true);
        diagnostics.add(stats).growX().left().pad(4).row();
        settingsScroll = new ScrollPane(content);
        settingsScroll.name = "cg.editor.settings";
        settingsScroll.setScrollingDisabled(true, false);
        settingsScroll.setFlickScroll(false);
        add(toolbar).growX().left().top().row();
        add(settingsScroll).grow().minHeight(0);
        refreshToolbar(8);
        applyExpanded();
    }

    private double automaticLargest() { return Math.max(.01, hud.profiles.largestHitSize); }

    private CheckBox flag(Table target, String key, BooleanSupplier get, Consumer<Boolean> set) {
        CheckBox control = toggle(hud, key, get, value -> { set.accept(value); hud.changed(); });
        UiControls.row(hud, target, key, control);
        return control;
    }

    private Numeric numeric(String key, DoubleSupplier get, DoubleSupplier def, double min, double max, DoubleConsumer set) {
        return number(hud, key, get, def, min, max, false, value -> { set.accept(value); hud.changed(); });
    }

    private void numberRow(Table target, String key, DoubleSupplier get, DoubleSupplier def,
                           double min, double max, DoubleConsumer set) {
        UiControls.row(hud, target, key, numeric(key, get, def, min, max, set));
    }

    private Table combination(String key, BooleanSupplier get, Consumer<Boolean> set, Numeric field) {
        CheckBox toggle = toggle(hud, key, get, set);
        Table controls = new Table() {
            private boolean narrow;
            @Override public void layout() {
                boolean next = getWidth() < toggle.getPrefWidth() +
                    (COMBINATION_GAP + FIELD_WIDTH + RESET_WIDTH) * hud.layout.scale;
                if (next != narrow) { narrow = next; arrange(); }
                super.layout();
            }
            private void arrange() {
                clearChildren();
                add(toggle).left().padRight(narrow ? 0 : COMBINATION_GAP);
                if (narrow) row();
                add(field).growX().minWidth(0).left();
            }
        };
        // Start with the same actors; resizing only changes the two cell positions.
        controls.left();
        controls.add(toggle).left().padRight(COMBINATION_GAP);
        controls.add(field).growX().minWidth(0).left();
        return controls;
    }

    private Collapser section(Table parent, String key, Table body, boolean open, boolean primary) {
        // Arc clips the drawing of an animating Collapser, but not its children's hit areas.
        Collapser collapser = new Collapser(body, !open) {
            @Override public Element hit(float x, float y, boolean touchable) {
                if (x < 0 || x >= getWidth() || y < 0 || y >= Math.min(getHeight(), getPrefHeight())) return null;
                return super.hit(x, y, touchable);
            }
        }.setDuration(.15f);
        Button header = new Button(new Button.ButtonStyle());
        header.name = "cg.section." + key;
        var icon = new Image(open ? downArrow : rightArrow, Scaling.none);
        icon.name = "cg.section-icon." + key;
        header.add(icon).size(DISCLOSURE_SLOT).padRight(4);
        Label label = new Label(text(key));
        label.name = "cg.section-label." + key;
        label.setFontScale(primary ? 1.12f : 1.04f); label.setWrap(true);
        header.add(label).growX().left();
        header.clicked(() -> { collapser.toggle(); if (key.equals("telemetry")) hud.setTelemetry(!collapser.isCollapsed()); });
        header.update(() -> {
            icon.setDrawable(collapser.isCollapsed() ? rightArrow : downArrow);
            icon.setColor(collapser.isCollapsed() ? Color.white : Pal.accent);
            label.setColor(header.isOver() ? Pal.accent : Color.white);
        });
        parent.add(header).growX().minWidth(0).padTop(primary ? 9 : 6).padBottom(3).row();
        parent.add(collapser).growX().minWidth(0).padLeft(sectionIndent).row();
        return collapser;
    }

    boolean telemetryOpen() { return hud.settings.editorExpanded && !telemetry.isCollapsed(); }

    private static TextureRegionDrawable disclosure(String name) {
        var region = ((TextureRegionDrawable)GroupsHud.drawable(name)).getRegion();
        TextureRegionDrawable arrow = new TextureRegionDrawable(region);
        // Use the same glyph texture, but bypass Fonts.getGlyph's natural-size draw override.
        arrow.setMinWidth(region.width / 2f); arrow.setMinHeight(region.height / 2f);
        return arrow;
    }

    private void applyExpanded() {
        boolean expanded = hud.settings.editorExpanded;
        settingsScroll.visible = expanded;
        setBackground(expanded ? Tex.pane : null);
        if (!expanded) Core.scene.unfocus(settingsScroll);
        hud.setTelemetry(hud.editing && telemetryOpen());
    }

    private void refreshToolbar(int columns) {
        toolbarColumns = columns;
        toolbar.clearChildren();
        ImageButton move = button("move", "move", () -> {});
        move.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (pointer != 0 || button != KeyCode.mouseLeft) return false;
                hud.beginMove(event.stageX, event.stageY); event.stop(); return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                hud.drag(event.stageX, event.stageY); event.stop();
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                hud.finishDrag(); event.stop();
            }
        });
        if (1 % columns == 0) toolbar.row();
        button("refresh", "rotate", hud::rotate);
        if (2 % columns == 0) toolbar.row();
        direction = button("flipY", "reverse", () -> { hud.settings.reverse = !hud.settings.reverse; hud.changed(); });
        if (3 % columns == 0) toolbar.row();
        button("list", "units", hud::openUnits);
        if (4 % columns == 0) toolbar.row();
        eye = button("eye", "projection", () -> { hud.settings.projection = !hud.settings.projection; hud.changed(); });
        if (5 % columns == 0) toolbar.row();
        collapse = button("left", "collapse", () -> {
            if (hud.layout.dock != null) { hud.settings.setCollapse(!hud.settings.collapse); hud.changed(); }
        });
        if (6 % columns == 0) toolbar.row();
        schematic = button("grid", "editor-schematic", () -> hud.settings.setEditorSchematic(!hud.settings.editorSchematic));
        if (7 % columns == 0) toolbar.row();
        expand = button("bookOpen", "editor-expanded", () -> {
            hud.settings.setEditorExpanded(!hud.settings.editorExpanded); applyExpanded();
        });
    }

    private ImageButton button(String icon, String key, Runnable action) {
        ImageButton button = iconButton(hud, icon, key, action);
        button.name = "cg.editor." + key;
        button.getImageCell().size(toolbarButtonSize - 2 * BUTTON_INSET);
        toolbar.add(button).size(toolbarButtonSize).padTop(2).padBottom(2).padRight(BUTTON_GAP);
        return button;
    }

    void positionPanel() {
        if (hud.layout.scale <= 0) return;
        float margin = 8f * hud.layout.scale, start = hud.layout.zeroSize + hud.settings.zeroGap * hud.layout.scale;
        float px = hud.x + margin, py = hud.y + margin, w = hud.getWidth() - 2 * margin, h = hud.getHeight() - 2 * margin;
        if (hud.settings.vertical) { h -= start; if (hud.settings.reverse) py += start; }
        else { w -= start; if (!hud.settings.reverse) px += start; }
        w = Math.min(Math.max(MIN_PANEL_WIDTH * hud.layout.scale, w), Core.scene.getWidth());
        h = Math.min(Math.max(180f * hud.layout.scale, h), Core.scene.getHeight());
        if (hud.layout.centerSide == HudLayout.Side.LEFT) px = hud.x + hud.getWidth() - margin - w;
        if (hud.layout.centerSide == HudLayout.Side.BOTTOM) py = hud.y + hud.getHeight() - margin - h;
        setBounds(Mathf.clamp(px, 0, Math.max(0, Core.scene.getWidth() - w)),
            Mathf.clamp(py, 0, Math.max(0, Core.scene.getHeight() - h)), Math.max(1, w), Math.max(1, h));
        int columns = Math.max(1, Math.min(8, (int)((w / hud.layout.scale - 8) / (toolbarButtonSize + BUTTON_GAP))));
        if (columns != toolbarColumns) refreshToolbar(columns);
    }

    @Override public Element hit(float x, float y, boolean touchable) {
        if (hud.onEditorEdge(this.x + x - hud.x, this.y + y - hud.y)) return null;
        if (!hud.settings.editorExpanded) return toolbar.hit(x - toolbar.x, y - toolbar.y, touchable);
        return super.hit(x, y, touchable);
    }

    @Override public void act(float delta) {
        if (!visible) return;
        super.act(delta);
        direction.getStyle().imageUp = GroupsHud.drawable(hud.settings.vertical ? "flipY" : "flipX");
        eye.getStyle().imageUp = GroupsHud.drawable(hud.settings.projection ? "eye" : "eyeOff");
        eye.setChecked(hud.settings.projection);
        schematic.setChecked(hud.settings.editorSchematic); expand.setChecked(hud.settings.editorExpanded);
        collapse.setDisabled(hud.layout.dock == null); collapse.setChecked(hud.settings.collapse);
        toggleColor(eye, hud.settings.projection);
        toggleColor(collapse, hud.settings.collapse);
        toggleColor(schematic, hud.settings.editorSchematic);
        toggleColor(expand, hud.settings.editorExpanded);
        collapse.getStyle().imageUp = GroupsHud.drawable(hud.layout.dock == null ? "cancel" : switch (hud.layout.dock) {
            case LEFT -> "left"; case RIGHT -> "right"; case TOP -> "up"; case BOTTOM -> "down";
        });
        if (telemetryOpen() && hud.model.telemetry) {
            statsTimer -= delta;
            if (statsTimer <= 0) {
                statsTimer = 1f;
                stats.setText(String.format(java.util.Locale.ROOT, text("stats"), hud.model.observedUnits(),
                    hud.model.membershipNanos / 1_000_000.0, hud.model.stateNanos / 1_000_000.0,
                    hud.drawNanos / 1_000_000.0, hud.layout.rebuilds));
            }
        }
    }

    private static void toggleColor(ImageButton button, boolean active) {
        var style = button.getStyle();
        style.imageUpColor = active ? Color.white : Color.gray;
        style.imageOverColor = style.imageDownColor = style.imageCheckedColor = style.imageUpColor;
    }
}
