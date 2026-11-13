package controlgroups;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

import static controlgroups.UiControls.*;

/** One editor for the same HUD, not an alternate implementation of group drawing. */
final class HudEditor extends Table {
    private final GroupsHud hud;
    private final Table toolbar = new Table();
    private ImageButton eye, collapse, direction;
    private Label stats;
    private float statsTimer;

    HudEditor(GroupsHud hud) {
        this.hud = hud;
        name = "control-groups-editor";
        background(Tex.pane);
        Table content = new Table();
        content.add(toolbar).colspan(2).growX().left().row();
        var s = hud.settings;
        UiControls.check(content, "align", s.align, value -> { s.align = value; hud.changed(); });
        UiControls.check(content, "unassigned", s.unassigned, value -> { s.unassigned = value; hud.changed(); });
        UiControls.check(content, "unassigned-first", s.unassignedFirst, value -> { s.unassignedFirst = value; hud.changed(); });
        UiControls.check(content, "hover", s.hover, value -> { s.hover = value; hud.changed(); });
        UiControls.check(content, "adapt", s.adapt, value -> { s.adapt = value; hud.changed(); });
        UiControls.check(content, "advanced", s.advanced, value -> { s.advanced = value; hud.changed(); });
        UiControls.check(content, "motion", s.checkMotion, value -> { s.checkMotion = value; hud.changed(); });
        number(content, "base", s.baseSize, 1, 1024, value -> { s.baseSize = (float)value; hud.changed(); });
        number(content, "compression", s.compression * 100, 0.1, 100, value -> { s.compression = (float)value / 100; hud.changed(); });
        number(content, "occupancy", s.occupancy * 100, 1, 100, value -> { s.occupancy = (float)value / 100; hud.changed(); });
        number(content, "max-projection", s.maxProjection, 0, 2048, value -> { s.maxProjection = (float)value; hud.changed(); });
        number(content, "zero-gap", s.zeroGap, 0, 100, value -> { s.zeroGap = (float)value; hud.changed(); });
        number(content, "group-gap", s.groupGap, 0, 100, value -> { s.groupGap = (float)value; hud.changed(); });
        number(content, "tier-gap", s.tierGap, 0, 100, value -> { s.tierGap = (float)value; hud.changed(); });
        number(content, "type-gap", s.typeGap, 0, 100, value -> { s.typeGap = (float)value; hud.changed(); });
        number(content, "damage-ticks", s.damageTicks, 0, 3600, value -> { s.damageTicks = (float)value; hud.changed(); });
        content.add(text("editor-help")).colspan(2).wrap().width(208).pad(5).row();
        stats = new Label("");
        content.add(stats).colspan(2).left().wrap().width(208).pad(5).row();
        ScrollPane scroll = new ScrollPane(content);
        scroll.setScrollingDisabled(true, false);
        add(scroll).grow().pad(4);
        refreshToolbar();
    }

    void refreshToolbar() {
        toolbar.clearChildren();
        int columns = hud.settings.vertical ? Math.max(1, (int)(hud.settings.breadth / 38)) :
            Math.max(1, (int)Math.ceil(6f / Math.max(1, (int)(hud.settings.breadth / 38))));
        ImageButton move = button("move", "move", () -> {});
        move.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (pointer != 0 || button != KeyCode.mouseLeft) return false;
                hud.beginMove(event.stageX, event.stageY); return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                hud.drag(event.stageX, event.stageY);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                hud.finishDrag();
            }
        });
        if (1 % columns == 0) toolbar.row();
        button("refresh", "rotate", hud::rotate);
        if (2 % columns == 0) toolbar.row();
        direction = button(hud.settings.vertical ? "flip-y" : "flip-x", "reverse", () -> {
            hud.settings.reverse = !hud.settings.reverse; hud.changed();
        });
        if (3 % columns == 0) toolbar.row();
        button("list", "units", hud::openUnits);
        if (4 % columns == 0) toolbar.row();
        eye = button(hud.settings.projection ? "eye" : "eye-off", "projection", () -> {
            hud.settings.projection = !hud.settings.projection; hud.changed();
        });
        if (5 % columns == 0) toolbar.row();
        collapse = button("cancel", "collapse", () -> {
            if (hud.layout.dock != null) { hud.settings.collapse = !hud.settings.collapse; hud.changed(); }
        });
    }

    private ImageButton button(String icon, String key, Runnable clicked) {
        ImageButton button = toolbar.button(GroupsHud.drawable(icon), Styles.emptyi, clicked).size(34).pad(1).get();
        button.setStyle(new ImageButton.ImageButtonStyle(button.getStyle()));
        button.addListener(new Tooltip(t -> t.background(Tex.pane).add(text(key)).wrap().width(220).pad(6)));
        return button;
    }

    void positionPanel() {
        if (hud.layout.scale <= 0) return;
        float margin = 8f * hud.layout.scale, start = hud.layout.zeroSize + hud.settings.zeroGap * hud.layout.scale;
        float px = hud.x + margin, py = hud.y + margin, w = hud.getWidth() - 2 * margin, h = hud.getHeight() - 2 * margin;
        if (hud.settings.vertical) {
            h -= start;
            if (hud.settings.reverse) py += start;
        } else {
            w -= start;
            if (!hud.settings.reverse) px += start;
        }
        setBounds(px, py, Math.max(1, w), Math.max(1, h));
    }

    @Override
    public void act(float delta) {
        if (!visible) return;
        super.act(delta);
        if (eye == null) return;
        eye.getStyle().imageUp = GroupsHud.drawable(hud.settings.projection ? "eye" : "eye-off");
        // Each button owns a style copy; emptyi itself is shared by the game and other mods.
        direction.setChecked(hud.settings.reverse);
        eye.setChecked(hud.settings.projection);
        collapse.setDisabled(hud.layout.dock == null);
        collapse.setChecked(hud.settings.collapse);
        direction.getStyle().imageUpColor = hud.settings.reverse ? mindustry.graphics.Pal.accent : arc.graphics.Color.white;
        eye.getStyle().imageUpColor = hud.settings.projection ? mindustry.graphics.Pal.accent : arc.graphics.Color.lightGray;
        collapse.getStyle().imageUpColor = hud.settings.collapse ? mindustry.graphics.Pal.accent : arc.graphics.Color.white;
        collapse.getStyle().imageUp = GroupsHud.drawable(hud.layout.dock == null ? "cancel" : switch (hud.layout.dock) {
            case LEFT -> "left"; case RIGHT -> "right"; case TOP -> "up"; case BOTTOM -> "down";
        });
        statsTimer -= delta;
        if (statsTimer <= 0) {
            statsTimer = 1f;
            stats.setText(String.format(java.util.Locale.ROOT, text("stats"), hud.model.observedUnits(),
                hud.model.membershipNanos / 1_000_000.0, hud.model.stateNanos / 1_000_000.0,
                hud.drawNanos / 1_000_000.0, hud.layout.rebuilds));
        }
    }
}
