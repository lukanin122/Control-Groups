package controlgroups;

import arc.Core;
import arc.scene.ui.CheckBox;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Shared validation and spacing for both settings panels. */
final class UiControls {
    static String text(String key) {
        return Core.bundle.get("cg." + key);
    }

    static void check(Table table, String key, boolean value, Consumer<Boolean> change) {
        CheckBox box = new CheckBox(text(key));
        box.setChecked(value);
        box.changed(() -> change.accept(box.isChecked()));
        table.add(box).colspan(2).left().pad(4).row();
    }

    static void number(Table table, String key, double value, double min, double max, DoubleConsumer change) {
        table.add(text(key)).left().wrap().width(128).pad(4);
        TextField field = numberField(value, min, max, false, change);
        table.add(field).width(72).pad(4).row();
    }

    static TextField numberField(double value, double min, double max, boolean integer, DoubleConsumer change) {
        TextField field = new TextField(format(value));
        field.setValidator(text -> validNumber(text, min, max, integer));
        field.changed(() -> {
            String text = field.getText();
            if (validNumber(text, min, max, integer)) change.accept(Double.parseDouble(text.replace(',', '.')));
        });
        return field;
    }

    static boolean validNumber(String text, double min, double max, boolean integer) {
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            return Double.isFinite(value) && value >= min && value <= max && (!integer || value == Math.rint(value));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long)value) : Float.toString((float)value);
    }

    private UiControls() {
    }
}
