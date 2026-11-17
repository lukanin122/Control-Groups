package controlgroups;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.style.BaseDrawable;
import arc.scene.style.Drawable;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Tmp;
import mindustry.ui.Styles;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** The same row, field, reset and tooltip rules serve the editor and per-type settings. */
final class UiControls {
    static final float FIELD_WIDTH = 88f;
    static final float BUTTON_SIZE = 40f, BUTTON_GAP = 4f, FIELD_RESET_GAP = 3f, COMBINATION_GAP = 5f;
    static final float BUTTON_INSET = 5f;
    static final float BUTTON_BORDER = 2f;
    static final float DISCLOSURE_SLOT = 20f;
    static final float RESET_WIDTH = BUTTON_SIZE + FIELD_RESET_GAP;
    private static final Drawable buttonUp = new ButtonBorder(.55f), buttonOver = new ButtonBorder(.75f), buttonDown = new ButtonBorder(.9f);
    static String text(String key) { return Core.bundle.get("cg." + key); }

    static void tooltip(GroupsHud hud, Element element, String message) {
        if (message == null || message.isEmpty()) return;
        Tooltip tooltip = new Tooltip(t -> t.background(Styles.black8).margin(4).add(message).color(Color.lightGray)) {
            { allowMobile = true; }
            @Override protected void setContainerPosition(Element actor, float x, float y) {
                Label label = (Label)container.getChildren().first();
                label.setWrap(false);
                float width = Math.min(label.getPrefWidth(), Math.max(1, hud.tooltipWidth() - container.getMarginLeft() - container.getMarginRight()));
                label.setWrap(true);
                container.getCell(label).width(width / Scl.scl(1));
                // Mindustry UI.addDescTooltip, also used by BuildWheel: below the actor's left edge.
                targetActor = actor;
                Vec2 position = actor.localToStageCoordinates(Tmp.v1.setZero());
                container.pack();
                container.setPosition(position.x, position.y, Align.topLeft);
                container.setOrigin(0, actor.getHeight());
            }
        };
        tooltip.container.update(tooltip.container::keepInStage);
        element.addListener(tooltip);
    }

    static void help(GroupsHud hud, Element element, String key) {
        tooltip(hud, element, Core.bundle.get("cg." + key + "-help", ""));
    }

    static ImageButton.ImageButtonStyle buttonStyle(float borderScale) {
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
        style.up = style.checked = style.disabled = borderScale == 1 ? buttonUp : new ButtonBorder(.55f, borderScale);
        style.over = style.checkedOver = borderScale == 1 ? buttonOver : new ButtonBorder(.75f, borderScale);
        style.down = borderScale == 1 ? buttonDown : new ButtonBorder(.9f, borderScale);
        style.imageUpColor = style.imageDownColor = style.imageOverColor = style.imageCheckedColor = Color.white;
        style.imageDisabledColor = Color.gray;
        return style;
    }

    static ImageButton iconButton(GroupsHud hud, String icon, String tooltipKey, Runnable action) {
        return iconButton(hud, GroupsHud.drawable(icon), tooltipKey, action, buttonStyle(1), BUTTON_INSET);
    }

    static ImageButton iconButton(GroupsHud hud, Drawable icon, String tooltipKey, Runnable action,
                                  ImageButton.ImageButtonStyle style, float inset) {
        style.imageUp = icon;
        ImageButton button = new ImageButton(style);
        button.margin(inset);
        // Fonts.getGlyph ignores the requested drawing size. Give its natural glyph a truthful box.
        button.getImageCell().size(Math.max(20, style.imageUp.imageSize()));
        button.clicked(action);
        tooltip(hud, button, text(tooltipKey));
        return button;
    }

    static CheckBox toggle(GroupsHud hud, String key, BooleanSupplier get, Consumer<Boolean> set) {
        CheckBox box = new CheckBox("");
        box.left(); box.getLabelCell().padLeft(0);
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
        line.marginLeft(DISCLOSURE_SLOT);
        Label label = new Label(text(key));
        label.name = "cg.label." + key;
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
            left();
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
            add(field).width(FIELD_WIDTH).minWidth(36).fillX().left();
            add(reset).size(BUTTON_SIZE).padLeft(FIELD_RESET_GAP);
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

    /** BuildWheel's number-key treatment: a transparent, inward border with brightness-only feedback. */
    private static final class ButtonBorder extends BaseDrawable {
        private final float brightness;
        private final float scale;
        ButtonBorder(float brightness) { this(brightness, 1); }
        ButtonBorder(float brightness, float scale) { this.brightness = brightness; this.scale = scale; }
        @Override public void draw(float x, float y, float width, float height) {
            float color = Draw.getColorPacked(), thickness = Math.min(Scl.scl(BUTTON_BORDER * scale), Math.min(width, height) / 2);
            try {
                Draw.color(brightness, brightness, brightness, Draw.getColor().a);
                Fill.rect(x + width / 2, y + thickness / 2, width, thickness);
                Fill.rect(x + width / 2, y + height - thickness / 2, width, thickness);
                Fill.rect(x + thickness / 2, y + height / 2, thickness, Math.max(0, height - 2 * thickness));
                Fill.rect(x + width - thickness / 2, y + height / 2, thickness, Math.max(0, height - 2 * thickness));
            } finally { Draw.color(color); }
        }
    }
    private UiControls() {}
}
