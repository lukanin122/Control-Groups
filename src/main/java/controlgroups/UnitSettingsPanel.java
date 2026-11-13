package controlgroups;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

import static controlgroups.UiControls.*;

final class UnitSettingsPanel extends Table {
    private final GroupsHud hud;
    private boolean built;
    boolean open;

    UnitSettingsPanel(GroupsHud hud) {
        this.hud = hud;
        name = "control-groups-unit-settings";
        background(Tex.pane);
    }

    @Override
    public void act(float delta) {
        if (visible) super.act(delta);
    }

    private void build() {
        built = true;
        Table title = new Table();
        title.add(text("units")).growX().left().pad(6);
        title.button(Icon.cancel, Styles.emptyi, () -> { open = false; Core.scene.unfocus(this); }).size(36);
        add(title).growX().row();
        Table list = new Table();
        list.add("").width(38);
        list.add(text("color")).width(94);
        list.add(text("tier")).width(60);
        list.add(text("type")).width(60);
        list.add(text("resize")).width(76).row();
        for (var profile : hud.profiles.controllable) {
            var image = list.image(profile.unit.uiIcon).size(32).pad(3).get();
            image.addListener(new Tooltip(t -> t.background(Tex.pane).add(profile.unit.localizedName + "\n" + profile.unit.name).pad(6)));
            TextField color = new TextField("#" + profile.color.toString().substring(0, 6));
            color.setValidator(UnitProfiles::validColor);
            color.changed(() -> {
                if (UnitProfiles.validColor(color.getText())) {
                    profile.color.set(Color.valueOf(color.getText().replace("#", ""))); hud.profileChanged(profile);
                }
            });
            list.add(color).width(94).pad(2);
            list.add(numberField(profile.tier, 1, 1024, true, value -> {
                profile.tier = (int)value; hud.profileChanged(profile);
            })).width(60).pad(2);
            list.add(numberField(profile.order, 1, 1024, true, value -> {
                profile.order = (int)value; hud.profileChanged(profile);
            })).width(60).pad(2);
            list.add(numberField(profile.resize * 100, 1, 2000, false, value -> {
                profile.resize = (float)value / 100f; hud.profileChanged(profile);
            })).width(76).pad(2).row();
        }
        ScrollPane scroll = new ScrollPane(list);
        // Horizontal scrolling also keeps the complete row accessible in unusually small windows.
        scroll.setScrollingDisabled(false, false);
        add(scroll).grow().pad(5);
    }

    void positionPanel() {
        if (!open) return;
        if (!built) build();
        float sw = Core.scene.getWidth(), sh = Core.scene.getHeight(), scale = hud.layout.scale;
        float size = Math.min(Math.max(hud.layout.length, 390 * scale), Math.min(sw, sh) - 12 * scale);
        size = Math.max(1, size);
        float px = hud.x, py = hud.y;
        switch (hud.layout.centerSide) {
            case LEFT -> px -= size + 4 * scale;
            case RIGHT -> px += hud.getWidth() + 4 * scale;
            case BOTTOM -> py -= size + 4 * scale;
            case TOP -> py += hud.getHeight() + 4 * scale;
        }
        setBounds(Mathf.clamp(px, 0, Math.max(0, sw - size)), Mathf.clamp(py, 0, Math.max(0, sh - size)), size, size);
    }
}
