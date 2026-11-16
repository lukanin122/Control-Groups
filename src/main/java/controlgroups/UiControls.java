package controlgroups;

import arc.Core;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** The same row, field, reset and tooltip rules serve the editor and per-type settings. */
final class UiControls {
    static final float FIELD_WIDTH = 88f;
    static String text(String key) { return Core.bundle.get("cg." + key); }

    static void tooltip(GroupsHud hud, Element element, String message) {
        if (message == null || message.isEmpty()) return;
        element.addListener(new Tooltip(t -> t.add(message).color(Color.lightGray)) {
            @Override protected void setContainerPosition(Element actor, float x, float y) {
                Label label = (Label)container.getChildren().first();
                label.setWrap(false);
                float width = Math.min(label.getPrefWidth(), Math.max(1, hud.tooltipWidth()));
                label.setWrap(true);
                container.getCell(label).width(width / Scl.scl(1));
                super.setContainerPosition(actor, x, y);
            }
        });
    }

    static void help(GroupsHud hud, Element element, String key) {
        tooltip(hud, element, Core.bundle.get("cg." + key + "-help", ""));
    }

    static ImageButton iconButton(GroupsHud hud, String icon, String tooltipKey, Runnable action) {
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
        style.up = Tex.pane; style.over = style.down = Styles.flatOver;
        style.checked = Tex.pane; style.checkedOver = Styles.flatOver;
        style.imageUp = GroupsHud.drawable(icon);
        style.imageUpColor = Color.white; style.imageOverColor = Color.white;
        style.imageCheckedColor = Pal.accent;
        ImageButton button = new ImageButton(style);
        button.getImageCell().size(20);
        button.clicked(action);
        tooltip(hud, button, text(tooltipKey));
        return button;
    }

    static CheckBox toggle(GroupsHud hud, String key, BooleanSupplier get, Consumer<Boolean> set) {
        CheckBox box = new CheckBox("");
        box.name = "cg.toggle." + key;
        box.setProgrammaticChangeEvents(false);
        box.setChecked(get.getAsBoolean());
        box.changed(() -> set.accept(box.isChecked()));
        box.update(() -> {
            boolean value = get.getAsBoolean();
            if (box.isChecked() != value) box.setChecked(value);
        });
        help(hud, box, key);
        return box;
    }

    static void row(GroupsHud hud, Table table, String key, Element control) {
        Table line = new Table();
        Label label = new Label(text(key));
        label.setWrap(true);
        line.add(label).uniformX().growX().minWidth(0).left().pad(4);
        Table controls = new Table().left();
        if (control instanceof Table && !(control instanceof CheckBox)) controls.add(control).growX().minWidth(0);
        else controls.add(control).left();
        line.add(controls).uniformX().growX().minWidth(0).pad(4);
        table.add(line).growX().row();
        help(hud, label, key);
    }

    static Numeric number(GroupsHud hud, String name, DoubleSupplier get, DoubleSupplier def,
                          double min, double max, boolean integer, DoubleConsumer set) {
        return new Numeric(hud, name, get, def, min, max, integer, set);
    }

    static final class Numeric extends Table {
        final TextField field;
        final ImageButton reset;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final DoubleSupplier def;
        private double displayed;
        private BooleanSupplier enabled = () -> true;

        Numeric(GroupsHud hud, String name, DoubleSupplier get, DoubleSupplier def,
                double min, double max, boolean integer, DoubleConsumer set) {
            this.get = get; this.def = def; this.set = set; displayed = get.getAsDouble();
            field = new TextField(format(displayed));
            field.name = "cg.value." + name;
            field.setProgrammaticChangeEvents(false);
            field.setValidator(value -> validNumber(value, min, max, integer));
            field.changed(() -> {
                String value = field.getText();
                if (validNumber(value, min, max, integer)) {
                    displayed = Double.parseDouble(value.replace(',', '.')); set.accept(displayed);
                }
            });
            reset = iconButton(hud, "refresh", "reset", this::resetValue);
            reset.name = "cg.reset." + name;
            add(field).width(FIELD_WIDTH).growX().minWidth(36);
            add(reset).size(28).padLeft(4);
            help(hud, field, name);
        }

        Numeric enabled(BooleanSupplier enabled) { this.enabled = enabled; return this; }

        void resetValue() {
            double value = def.getAsDouble();
            set.accept(value); displayed = get.getAsDouble(); field.setText(format(displayed));
        }

        @Override public void act(float delta) {
            super.act(delta);
            field.setDisabled(!enabled.getAsBoolean());
            double value = get.getAsDouble();
            if (Core.scene.getKeyboardFocus() != field && (value != displayed || !field.isValid())) {
                displayed = value; field.setText(format(value));
            }
        }
    }

    static boolean validNumber(String text, double min, double max, boolean integer) {
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            boolean withinMax = value <= max || (max == Float.MAX_VALUE && Float.isFinite((float)value));
            return Double.isFinite(value) && value >= min && withinMax && (!integer || value == Math.rint(value));
        } catch (NumberFormatException exception) { return false; }
    }

    static String format(double value) {
        return value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE ?
            Long.toString((long)value) : Float.toString((float)value);
    }
    private UiControls() {}
}
