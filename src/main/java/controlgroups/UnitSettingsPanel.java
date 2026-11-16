package controlgroups;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

import static controlgroups.UiControls.*;

/** Per-content preferences. Rows include every registered type, even when automatically banned. */
final class UnitSettingsPanel extends Table {
    static final float COLOR_WIDTH = 136f * .9f, INDEX_WIDTH = FIELD_WIDTH * .7f;
    private final GroupsHud hud;
    private boolean built;
    boolean open;

    UnitSettingsPanel(GroupsHud hud) {
        this.hud = hud;
        name = "control-groups-unit-settings";
        background(Tex.pane);
    }

    @Override public void act(float delta) { if (visible) super.act(delta); }

    private void build() {
        built = true;
        Table title = new Table();
        title.add(text("units")).growX().left().pad(6);
        var close = iconButton(hud, "cancel", "close", () -> { open = false; Core.scene.unfocus(this); });
        title.add(close).size(36);
        add(title).growX().row();
        Table list = new Table();
        list.add("").width(38);
        list.add(text("color")).width(COLOR_WIDTH + 32);
        list.add(text("tier")).width(INDEX_WIDTH + 32);
        list.add(text("type")).width(INDEX_WIDTH + 32);
        list.add(text("resize")).width(96 + 32);
        var banTitle = list.add(text("ban")).width(48).get();
        help(hud, banTitle, "ban"); list.row();
        for (var profile : hud.profiles.all) {
            String key = "unit." + profile.unit.name + ".";
            var image = list.image(profile.unit.uiIcon).size(32).pad(3).get();
            tooltip(hud, image, profile.unit.localizedName + "\n" + profile.unit.name);
            list.add(colorField(profile, key)).width(COLOR_WIDTH + 32).pad(2);
            list.add(number(hud, key + "tier", () -> profile.tier, () -> profile.defaultTier, 1, 1024, true, value -> {
                profile.tier = (int)value; hud.profileChanged(profile);
            })).width(INDEX_WIDTH + 32).pad(2);
            list.add(number(hud, key + "type", () -> profile.order, () -> profile.defaultOrder, 1, 1024, true, value -> {
                profile.order = (int)value; hud.profileChanged(profile);
            })).width(INDEX_WIDTH + 32).pad(2);
            list.add(number(hud, key + "resize", () -> profile.resize * 100, () -> 100, 1, 2000, false, value -> {
                profile.resize = (float)value / 100; hud.profileChanged(profile);
            })).width(96 + 32).pad(2);

            ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
            style.up = style.over = style.down = style.checked = Styles.defaultField.background;
            style.imageUp = null; style.imageChecked = Icon.ok;
            style.imageCheckedColor = Color.white;
            ImageButton ban = new ImageButton(style);
            ban.name = "cg.ban." + profile.unit.name;
            ban.getImageCell().size(24);
            ban.setProgrammaticChangeEvents(false); ban.setChecked(profile.banned);
            ban.changed(() -> { profile.banned = ban.isChecked(); hud.profileChanged(profile); });
            ban.update(() -> ban.setChecked(profile.banned));
            help(hud, ban, "ban");
            list.add(ban).width(48).height(40).pad(2).row();
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setScrollingDisabled(false, false);
        scroll.setFlickScroll(false);
        add(scroll).grow().pad(5);
    }

    private Table colorField(UnitProfiles.Profile profile, String key) {
        Table control = new Table();
        TextField.TextFieldStyle style = new TextField.TextFieldStyle(Styles.defaultField);
        style.fontColor = new Color(profile.color); style.focusedFontColor = style.fontColor;
        TextField field = new TextField(colorText(profile.color), style);
        field.name = "cg.value." + key + "color";
        field.setValidator(UnitProfiles::validColor);
        field.changed(() -> {
            if (UnitProfiles.validColor(field.getText())) {
                profile.color.set(Color.valueOf(field.getText().replace("#", "")));
                style.fontColor.set(profile.color); hud.profileChanged(profile);
            }
        });
        var reset = iconButton(hud, "refresh", "reset", () -> {
            profile.color.set(profile.defaultColor); style.fontColor.set(profile.color);
            field.setText(colorText(profile.color)); hud.profileChanged(profile);
        });
        reset.name = "cg.reset." + key + "color";
        control.add(field).width(COLOR_WIDTH).minWidth(0).growX();
        control.add(reset).size(28).padLeft(4);
        return control;
    }

    private static String colorText(Color color) { return "#" + color.toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT); }

    void positionPanel() {
        if (!open) return;
        if (!built) build();
        float sw = Core.scene.getWidth(), sh = Core.scene.getHeight(), scale = hud.layout.scale;
        float size = Math.max(1, Math.min(Math.max(hud.layout.length, 640 * scale), Math.min(sw, sh) - 12 * scale));
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
