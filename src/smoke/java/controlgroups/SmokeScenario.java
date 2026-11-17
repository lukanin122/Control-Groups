package controlgroups;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.geom.Rect;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.InputEvent;
import arc.scene.ui.Dialog;
import arc.scene.ui.CheckBox;
import arc.scene.ui.TextField;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Image;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import arc.scene.ui.Tooltip;
import arc.math.geom.Vec2;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.ScreenUtils;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.Version;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;

import java.util.Arrays;
import java.util.Locale;

/** Actual Mindustry client and OpenGL renderer with isolated data; never touches a user's world. */
public final class SmokeScenario implements java.util.function.LongConsumer {
    private final Fi reports;
    private boolean prepared;
    private int frames, stage;
    private GroupsHud hud;
    private final Seq<Unit> army = new Seq<>();
    private final long[] baseline = new long[240], enabled = new long[240];

    public SmokeScenario(Fi reports) {
        this.reports = reports; reports.mkdirs();
        reports.child("FAILED.txt").delete();
    }

    @Override
    public void accept(long elapsed) {
        try {
            hideDialogs();
            if (!prepared) { prepare(); return; }
            if (frames < 60) { frames++; return; }
            switch (stage) {
                case 0 -> {
                    if (frames < 300) baseline[frames - 60] = elapsed;
                    if (++frames >= 300) { stage++; frames = 0; Vars.control.input.commandMode = true; }
                }
                case 1 -> {
                    Vars.control.input.commandMode = true;
                    if (frames < 300) enabled[frames - 60] = elapsed;
                    if (++frames >= 300) {
                        screenshot("01-groups.png"); verifySelection(); stage++; frames = 0;
                        hud.toggleEditor();
                    }
                }
                case 2 -> {
                    if (frames++ == 60) { verifyEditor(); return; }
                    screenshot("02-editor.png"); hud.openUnits(); stage++; frames = 0;
                }
                case 3 -> {
                    if (frames++ == 60) { verifyUnitFields(); return; }
                    screenshot("03-unit-settings.png");
                    hud.toggleEditor(); Vars.control.input.commandMode = true;
                    hud.settings.setCollapse(true); hud.changed(); stage++; frames = 0;
                }
                case 4 -> {
                    screenshot("04-collapsed.png"); hud.settings.vertical = false; hud.settings.setCollapse(false);
                    hud.settings.xFraction = 0.5f; hud.settings.yFraction = 0; hud.changed(); stage++; frames = 0;
                }
                case 5 -> {
                    screenshot("05-horizontal.png");
                    hud.settings.reverse = true; hud.settings.advanced = true;
                    hud.profiles.get(UnitTypes.reign).resize = 4f; hud.profileChanged(hud.profiles.get(UnitTypes.reign));
                    hud.changed(); stage++; frames = 0;
                }
                case 6 -> {
                    screenshot("06-reverse-extended.png");
                    hud.settings.vertical = true; hud.settings.reverse = false; hud.settings.internalProjection = true;
                    hud.changed(); stage++; frames = 0;
                }
                case 7 -> {
                    screenshot("07-internal.png");
                    require(hud.layout.projectionReserved > 0 &&
                        (hud.layout.projectionSide == HudLayout.Side.TOP || hud.layout.projectionSide == HudLayout.Side.BOTTOM),
                        "Internal projections did not reserve longitudinal space");
                    require(Core.gl.glGetError() == 0, "OpenGL error after internal projection drawing");
                    Core.graphics.setWindowSize(640, 480);
                    hud.toggleEditor(); stage++; frames = 0;
                }
                case 8 -> {
                    require(Core.scene.getWidth() == 640 && Core.scene.getHeight() == 480,
                        "Client did not apply the 640x480 window resize");
                    Core.graphics.setWindowSize(320, 240); stage++; frames = 0;
                }
                case 9 -> {
                    if (frames++ == 60) { verifySmallEditor(); return; }
                    screenshot("08-small-editor.png");
                    Vars.logic.reset(); require(hud.model.observedUnits() == 0, "World reset did not clear observations");
                    report(); Core.app.exit();
                }
                default -> throw new AssertionError("Unknown stage");
            }
        } catch (Throwable exception) {
            screenshot("FAILED.png");
            reports.child("FAILED.txt").writeString(arc.util.Strings.getStackTrace(exception));
            exception.printStackTrace();
            System.exit(2);
        }
    }

    private void prepare() {
        Element actor = Core.scene.find("control-groups-hud");
        require(actor instanceof GroupsHud, "Mod did not attach the HUD");
        require(!Vars.mods.hasContentErrors(), "A loaded mod reported content/script errors");
        hud = (GroupsHud)actor;
        for (Activity activity : Activity.values()) {
            if (activity.icon != null) require(mindustry.gen.Icon.icons.containsKey(activity.icon) ||
                (activity == Activity.MOVE && mindustry.gen.Icon.left != null), "Missing activity icon: " + activity.icon);
        }
        for (String icon : new String[]{"flipX", "flipY", "eyeOff", "move", "settings", "list", "grid", "bookOpen"}) {
            require(mindustry.gen.Icon.icons.containsKey(icon), "Missing editor icon: " + icon);
        }
        hud.settings.vertical = true; hud.settings.reverse = false; hud.settings.projection = true;
        hud.settings.collapseCommand = hud.settings.collapseBuild = false;
        hud.settings.showBuild = false; hud.settings.xFraction = 0; hud.settings.yFraction = 0.5f;
        hud.settings.length = 640; hud.settings.breadth = 240; hud.settings.advanced = false;
        Core.settings.put("fpscap", 0);
        Core.settings.put("effects", false);
        Core.settings.put("commandmodehold", false);
        Vars.logic.reset();
        Vars.state.rules = new mindustry.game.Rules();
        Vars.world.loadGenerator(96, 96, tiles -> {
            tiles.fill();
            tiles.eachTile(tile -> tile.setFloor(Blocks.stone.asFloor()));
            tiles.getn(20, 20).setBlock(Blocks.coreShard, Team.sharded, 0);
        });
        Vars.state.rules.waves = false;
        Vars.state.rules.unitCap = 20000;
        Vars.state.rules.infiniteResources = true;
        Vars.logic.play();
        Vars.player.team(Team.sharded);
        Vars.player.unit(UnitTypes.alpha.spawn(Team.sharded, 160, 160));
        for (int i = 0; i < 1000; i++) {
            var type = switch (i % 7) {
                case 0 -> UnitTypes.dagger; case 1 -> UnitTypes.reign; case 2 -> UnitTypes.nova;
                case 3 -> UnitTypes.flare; case 4 -> UnitTypes.mega; case 5 -> UnitTypes.stell; default -> UnitTypes.atrax;
            };
            Unit unit = type.spawn(Team.sharded, 80 + i % 40 * 14f, 80 + i / 40 * 14f);
            army.add(unit);
        }
        for (int i = 0; i < 10; i++) Vars.control.input.controlGroups[i] = new IntSeq();
        for (int i = 0; i < army.size; i++) Vars.control.input.controlGroups[i % 10].add(army.get(i).id);
        // Distinct UI rows, predictable click targets and a large representative.
        Vars.control.input.controlGroups[0].clear();
        Vars.control.input.controlGroups[0].add(army.get(0).id, army.get(1).id);
        hud.settings.unassigned = true; hud.changed();
        Vars.control.input.commandMode = false;
        Core.camera.position.set(300, 300);
        prepared = true;
    }

    private void verifySelection() {
        require(hud.visible && !Vars.control.input.locked(), "HUD unavailable: visible=" + hud.visible +
            " command=" + Vars.control.input.commandMode + " game=" + Vars.state.isGame() +
            " shown=" + Vars.ui.hudfrag.shown() + " dialog=" + Core.scene.hasDialog());
        require(!hud.model.telemetry && hud.model.reconciliations == 0 && hud.drawNanos == 0,
            "Closed telemetry collected diagnostics");
        int observed = hud.model.observedUnits();
        Vars.control.input.commandMode = false; Core.scene.act(1f / 60f);
        require(!hud.visible, "Disabled building HUD remained visible");
        Unit hiddenAddition = UnitTypes.dagger.spawn(Team.sharded, 180, 180);
        Vars.control.input.controlGroups[9].add(hiddenAddition.id);
        Core.scene.act(1f / 60f);
        require(hud.model.observedUnits() == observed, "Hidden HUD kept scanning units");
        Vars.control.input.commandMode = true; Core.scene.act(1f / 60f);
        require(hud.visible && hud.model.observedUnits() == observed + 1, "HUD did not resume after command mode was restored");
        hud.settings.showBuild = true; hud.changed();
        Rect group = hud.layout.boxes[0].shown;
        Core.camera.position.set(300, 300);
        click(group.x + 1, group.y + 1);
        require(Vars.control.input.selectedUnits.size == 2, "Single click did not select both group members");
        require(Core.camera.position.epsilonEquals(300, 300, 0.01f), "Single click moved the camera");
        click(group.x + 1, group.y + 1);
        float expectedX = (army.get(0).x + army.get(1).x) / 2;
        float expectedY = (army.get(0).y + army.get(1).y) / 2;
        require(Core.camera.position.epsilonEquals(expectedX, expectedY, 0.01f), "Double click did not center camera");
        HudLayout.Cell cell = hud.layout.boxes[0].cells[0];
        click(cell.rect.x + cell.rect.width / 2, cell.rect.y + cell.rect.height / 2);
        require(Vars.control.input.selectedUnits.size == 1 && Vars.control.input.selectedUnits.first().type == cell.bucket.profile.unit,
            "Type click selected the wrong members");
        Rect projection = hud.layout.boxes[0].projection;
        click(projection.x + projection.width / 2, projection.y + projection.height / 2);
        require(Vars.control.input.selectedUnits.size == 1 &&
            Vars.control.input.selectedUnits.first() == hud.model.groups[0].representative.unit, "Projection click selected wrong unit");
        require(hud.hit(-10, -10, true) == null, "HUD intercepted input outside its controls");
        for (var box : hud.layout.boxes) if (box.visible) for (int i = 0; i < box.cellCount; i++) {
            var c = box.cells[i]; var ui = c.bucket.profile.uiIcon;
            if (ui != null && Math.max(c.icon.width, c.icon.height) * hud.layout.pixelRatio <= Math.max(ui.width, ui.height))
                require(c.region == ui, "A clipped small icon unnecessarily uses fullIcon");
        }
        verifyBuildingSelection(group);
        float color = Draw.getColorPacked(), mix = Draw.getMixColorPacked();
        float stroke = Lines.getStroke(), xs = Draw.xscl, ys = Draw.yscl, z = Draw.z();
        var blend = Draw.getBlend(); var shader = Draw.getShader();
        float[] transform = Draw.trans().val.clone(), projectionMatrix = Draw.proj().val.clone();
        hud.draw();
        require(Draw.getColorPacked() == color && Draw.getMixColorPacked() == mix, "HUD leaked draw state");
        require(Lines.getStroke() == stroke && Draw.xscl == xs && Draw.yscl == ys && Draw.z() == z &&
            Draw.getBlend() == blend && Draw.getShader() == shader && Arrays.equals(transform, Draw.trans().val) &&
            Arrays.equals(projectionMatrix, Draw.proj().val), "HUD leaked renderer state");
    }

    private void verifySmallEditor() {
        Element editor = Core.scene.find("control-groups-editor");
        require(Core.scene.getWidth() == 320 && Core.scene.getHeight() == 240 && editor.visible &&
            editor.getWidth() <= 320 && editor.getHeight() <= 240, "Small-window editor bounds are incorrect");
        fireButton("cg.section.advanced-settings"); fireButton("cg.section.projection-settings");
        Element toggle = Core.scene.find("cg.toggle.manual-largest");
        Element field = Core.scene.find("cg.value.manual-size");
        showElement(field);
        Vec2 switchPos = toggle.localToStageCoordinates(new Vec2());
        Vec2 fieldPos = field.localToStageCoordinates(new Vec2());
        require(fieldPos.y + field.getHeight() <= switchPos.y + .01f, "Narrow combined row did not wrap the numeric field");
        Seq<Element> controls = new Seq<>(); collect(editor, controls);
        for (Element e : controls) if (e instanceof ScrollPane scroll)
            require(scroll.getWidget().getWidth() <= scroll.getScrollWidth() + .01f, "Small editor overflows horizontally");
    }

    private void verifyEditor() {
        require(hud.editing, "Editor did not open");
        verifyEditorVisuals();
        Element editor = Core.scene.find("control-groups-editor");
        Seq<Element> controls = new Seq<>(); collect(editor, controls);
        ScrollPane settingsScroll = null;
        for (Element element : controls) if (element instanceof ScrollPane scroll) {
            require(scroll.getWidget().getWidth() <= scroll.getScrollWidth() + 0.01f,
                "Editor content exceeds the horizontal viewport: " + scroll.getWidget().getWidth() + "/" + scroll.getScrollWidth());
            settingsScroll = scroll;
        }
        require(settingsScroll != null, "Editor scroll pane is missing");
        for (Element element : controls) if (element instanceof CheckBox check)
            require(check.getWidth() <= editor.getWidth(), "Editor switch overflow");
        verifyTooltipsAndResets(controls);
        // A real DesktopInput update clears commandMode when a field owns keyboard focus.
        fireButton("cg.section.group-icon-settings");
        for (int i = 0; i < 20; i++) Core.scene.act(.02f);
        editor.validate();
        TextField field = (TextField)Core.scene.find("cg.value.icon-size");
        hud.settings.showBuild = false; hud.changed();
        Core.scene.act(1f / 60f); showElement(field);
        Vec2 fieldPoint = field.localToStageCoordinates(new Vec2(field.getWidth() / 2, field.getHeight() / 2));
        nativeSceneClick(fieldPoint.x, fieldPoint.y, false);
        Vars.control.input.update(); Core.scene.act(1f / 60f);
        require(!Vars.control.input.commandMode && hud.visible && hud.editing && editor.visible &&
            Core.scene.getKeyboardFocus() == field && hud.settings.commandContext(), "Editing lost focus or its mode context");
        field.selectAll(); Core.scene.keyTyped('4'); Core.scene.keyTyped('8');
        require(hud.settings.iconSize == 48, "Keyboard editing did not reach the numeric setting");
        fireButton("cg.reset.icon-size");
        require(hud.settings.iconSize == 36 && field.getText().equals("36"),
            "Editor reset did not apply default: " + hud.settings.iconSize + "/" + field.getText());
        Core.scene.setKeyboardFocus(null);
        fireButton("cg.section.telemetry"); Core.scene.act(.02f);
        require(hud.model.telemetry && hud.layout.telemetry, "Expanded telemetry did not enable diagnostics");
        hud.model.reconcile(Vars.control.input.controlGroups, id -> mindustry.gen.Groups.unit.getByID(id),
            Team.sharded.data().units, Team.sharded, true);
        long updates = hud.model.reconciliations;
        require(updates > 0, "Telemetry counters are not collected when enabled");
        verifyGeometryInvalidation();
        fireButton("cg.section.telemetry");
        long closedUpdates = hud.model.reconciliations;
        Core.scene.act(.02f);
        hud.model.reconcile(Vars.control.input.controlGroups, id -> mindustry.gen.Groups.unit.getByID(id),
            Team.sharded.data().units, Team.sharded, true);
        require(!hud.model.telemetry && hud.model.reconciliations == closedUpdates, "Collapsed telemetry still collected counters");
        hud.settings.showBuild = true; hud.changed();
        settingsScroll.setScrollY(0); settingsScroll.updateVisualScroll();
        Core.scene.draw();
        float oldScroll = settingsScroll.getScrollY();
        float oldX = hud.settings.xFraction, oldY = hud.settings.yFraction, oldBreadth = hud.settings.breadth;
        Element move = Core.scene.find("cg.editor.move");
        require(move != null, "Move control is missing");
        Vec2 start = move.localToStageCoordinates(new Vec2(move.getWidth() / 2, move.getHeight() / 2));
        fire(move, InputEvent.InputEventType.touchDown, start.x, start.y);
        fire(move, InputEvent.InputEventType.touchDragged, start.x + 10000, start.y + 10000);
        fire(move, InputEvent.InputEventType.touchUp, start.x + 10000, start.y + 10000);
        Core.scene.act(1f / 60f);
        require(Math.abs(settingsScroll.getScrollY() - oldScroll) < 1f, "Moving the HUD also scrolled its settings");
        require(hud.x >= 0 && hud.y >= 0 && hud.x + hud.getWidth() <= Core.scene.getWidth() + 0.01f &&
            hud.y + hud.getHeight() <= Core.scene.getHeight() + 0.01f, "Move escaped the viewport");
        hud.settings.xFraction = oldX; hud.settings.yFraction = oldY; hud.changed(); Core.scene.act(1f / 60f);
        float edgeX = hud.x + hud.getWidth(), edgeY = hud.y + hud.getHeight() / 2;
        fire(hud, InputEvent.InputEventType.touchDown, edgeX, edgeY);
        fire(hud, InputEvent.InputEventType.touchDragged, edgeX - 40, edgeY);
        fire(hud, InputEvent.InputEventType.touchUp, edgeX - 40, edgeY);
        Core.scene.act(1f / 60f);
        require(hud.settings.breadth < oldBreadth, "Frame edge did not resize the HUD");
        hud.settings.breadth = oldBreadth; hud.changed(); Core.scene.act(1f / 60f);
        require(Core.scene.hit(hud.x + hud.getWidth() - 1, hud.y + hud.getHeight() / 2, true) == hud,
            "Editor panel intercepted the frame edge");
    }

    private void verifyEditorVisuals() {
        Element editor = Core.scene.find("control-groups-editor");
        ScrollPane body = (ScrollPane)Core.scene.find("cg.editor.settings");
        require(hud.settings.editorExpanded && hud.settings.editorSchematic, "New editor defaults are disabled");
        Core.scene.draw();
        String[] keys = {"move", "rotate", "reverse", "units", "projection", "collapse", "editor-schematic", "editor-expanded"};
        Rect[] before = new Rect[keys.length];
        for (int i = 0; i < keys.length; i++) {
            ImageButton button = (ImageButton)Core.scene.find("cg.editor." + keys[i]);
            Vec2 point = button.localToStageCoordinates(new Vec2());
            before[i] = new Rect(point.x, point.y, button.getWidth(), button.getHeight());
            Image image = button.getImage();
            float inset = image.x + image.getImageX();
            require(inset >= 5 * hud.layout.scale && button.getWidth() - inset - image.getImageWidth() >= 5 * hud.layout.scale,
                "Editor icon touches its border: " + keys[i]);
            require(button.getStyle().up != button.getStyle().over && button.getStyle().over != button.getStyle().down,
                "Border feedback states are identical");
        }
        ImageButton projection = (ImageButton)Core.scene.find("cg.editor.projection");
        ImageButton collapse = (ImageButton)Core.scene.find("cg.editor.collapse");
        require(projection.getStyle().imageUpColor == Color.white && collapse.getStyle().imageUpColor == Color.gray,
            "Toolbar switches do not use white/gray status");
        int revision = hud.settings.revision;
        Rect group = new Rect(hud.layout.boxes[0].full);
        fireButton("cg.editor.editor-schematic"); Core.scene.act(.02f);
        require(!hud.settings.editorSchematic && hud.settings.revision == revision && group.equals(hud.layout.boxes[0].full),
            "Schematic switch changed game geometry");
        fireButton("cg.editor.editor-schematic"); Core.scene.act(.02f);

        Button header = (Button)Core.scene.find("cg.section.main-settings");
        Label label = (Label)Core.scene.find("cg.section-label.main-settings");
        Image arrow = (Image)Core.scene.find("cg.section-icon.main-settings");
        float textX = label.x, center = arrow.x + arrow.getWidth() / 2;
        var naturalArrow = (arc.scene.style.TextureRegionDrawable)GroupsHud.drawable("downOpen");
        require(arrow.getImageWidth() == naturalArrow.getRegion().width / 2f &&
            arrow.getImageHeight() == naturalArrow.getRegion().height / 2f && arrow.color.equals(Pal.accent),
            "Disclosure glyph is not actually drawn at half size");
        float indent = arrow.getImageWidth();
        require(header.getStyle().over == null && header.getStyle().down == null, "Header still paints a hover background");
        fireButton("cg.section.main-settings"); Core.scene.act(.2f); Core.scene.draw();
        require(label.x == textX && arrow.x + arrow.getWidth() / 2 == center, "Header text/arrow jumps when collapsed");
        fireButton("cg.section.main-settings"); Core.scene.act(.2f); Core.scene.draw();
        float mainX = label.localToStageCoordinates(new Vec2()).x;
        float sectionX = Core.scene.find("cg.section-label.space-allocation").localToStageCoordinates(new Vec2()).x;
        float itemX = Core.scene.find("cg.label.space-proportional").localToStageCoordinates(new Vec2()).x;
        require(Math.abs(sectionX - mainX - indent) < .51f &&
            Math.abs(itemX - sectionX - indent) < .51f, "Nested rows lack consistent hierarchical indentation");

        fireButton("cg.section.group-icon-settings");
        TextField iconField = (TextField)Core.scene.find("cg.value.icon-size");
        showElement(iconField);
        CheckBox influence = (CheckBox)Core.scene.find("cg.toggle.icon-influence");
        float fieldX = iconField.localToStageCoordinates(new Vec2()).x;
        float toggleX = influence.getImage().localToStageCoordinates(new Vec2()).x;
        require(Math.abs(fieldX - toggleX) < .51f, "Fields and switches do not start in the same column");
        Element reset = Core.scene.find("cg.reset.icon-size");
        require(Math.abs(reset.localToStageCoordinates(new Vec2()).x - fieldX - iconField.getWidth() - 3 * hud.layout.scale) < .51f,
            "Numeric reset gap is not 3 UI pixels");
        fireButton("cg.section.group-icon-settings");
        fireButton("cg.section.advanced-settings"); fireButton("cg.section.projection-settings");
        TextField manual = (TextField)Core.scene.find("cg.value.manual-size"); showElement(manual);
        Element manualToggle = Core.scene.find("cg.toggle.manual-largest");
        require(Math.abs(manual.localToStageCoordinates(new Vec2()).x - manualToggle.localToStageCoordinates(new Vec2()).x -
            manualToggle.getWidth() - 5 * hud.layout.scale) < .51f, "Combined setting gap is not 5 UI pixels");
        fireButton("cg.section.projection-settings"); fireButton("cg.section.advanced-settings");

        fireButton("cg.section.telemetry"); Core.scene.act(.02f);
        Core.scene.setKeyboardFocus(iconField);
        fireButton("cg.editor.editor-expanded"); Core.scene.act(.02f); Core.scene.draw();
        require(!body.visible && ((arc.scene.ui.layout.Table)editor).getBackground() == null && !hud.model.telemetry &&
            Core.scene.getKeyboardFocus() != iconField, "Hidden settings retained background, telemetry or keyboard focus");
        require(editor.hit(editor.getWidth() * .75f, editor.getHeight() * .2f, true) == null,
            "Hidden settings intercept input outside toolbar controls");
        for (int i = 0; i < keys.length; i++) {
            Element button = Core.scene.find("cg.editor." + keys[i]); Vec2 point = button.localToStageCoordinates(new Vec2());
            require(Math.abs(point.x - before[i].x) < .01f && Math.abs(point.y - before[i].y) < .01f, "Toolbar moved when settings were hidden");
        }
        fireButton("cg.editor.editor-expanded"); Core.scene.act(.02f);
        require(body.visible && hud.model.telemetry && hud.settings.revision == revision, "Reopening settings lost section state or invalidated geometry");
        fireButton("cg.section.telemetry");
        body.setScrollY(0); body.updateVisualScroll(); Core.scene.draw();
        Rect z = hud.layout.zero;
        float[][] samples = {{z.x + 2, z.y + z.height / 2}, {z.x + z.width - 2, z.y + z.height / 2},
            {z.x + z.width / 2, z.y + 2}, {z.x + z.width / 2, z.y + z.height - 2}};
        for (float[] sample : samples) {
            Vec2 pixel = Core.scene.getViewport().project(new Vec2(hud.x + sample[0], hud.y + sample[1]));
            var image = ScreenUtils.getFrameBufferPixmap(Math.round(pixel.x), Math.round(pixel.y), 1, 1);
            try { require((image.get(0, 0) >>> 8) == (Pal.accent.rgba() >>> 8), "Editor frame obscures the settings button border"); }
            finally { image.dispose(); }
        }
    }

    private void verifyUnitFields() {
        Element panel = Core.scene.find("control-groups-unit-settings");
        require(panel != null && panel.visible, "Unit settings did not open");
        Seq<Element> controls = new Seq<>(); collect(panel, controls);
        int colors = 0;
        verifyTooltipsAndResets(controls);
        GlyphLayout glyph = new GlyphLayout();
        for (Element element : controls) if (element instanceof TextField field && field.getText().startsWith("#")) {
            glyph.setText(field.getStyle().font, "#FFFFFF");
            var background = field.getStyle().background;
            float padding = background == null ? 0 : background.getLeftWidth() + background.getRightWidth();
            require(field.getWidth() >= glyph.width + padding + 2, "Color field does not fit seven characters");
            String typeName = field.name.substring("cg.value.unit.".length(), field.name.length() - ".color".length());
            require(field.getStyle().fontColor.equals(hud.profiles.get(Vars.content.unit(typeName)).color), "Color field text is not tinted");
            colors++;
        }
        require(colors == Vars.content.units().size, "Not every registered unit is available in settings");
        boolean detailed = false;
        for (var profile : hud.profiles.all) {
            var ui = profile.uiIcon; var full = profile.fullIcon;
            if (ui == null || full == null || Math.max(full.width, full.height) <= Math.max(ui.width, ui.height)) continue;
            float threshold = Math.max(ui.width, ui.height);
            require(HudLayout.unitIcon(ui, full, threshold, true) == ui &&
                HudLayout.unitIcon(ui, full, threshold + 1, true) == full &&
                HudLayout.unitIcon(ui, full, threshold + 1, false) == ui &&
                HudLayout.unitIcon(ui, Core.atlas.find("error"), 10000, true) == ui, "Icon resolution fallback is incorrect");
            detailed = true; break;
        }
        require(detailed, "No detailed unit texture was tested");
        for (var type : new mindustry.type.UnitType[]{UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma,
            UnitTypes.evoke, UnitTypes.incite, UnitTypes.emanate}) require(hud.profiles.get(type).banned, "Core avatar is not banned: " + type.name);
        int saved = Vars.control.input.controlGroups[0].size;
        fireButton("cg.ban.dagger"); Core.scene.act(.02f);
        require(hud.profiles.get(UnitTypes.dagger).banned && hud.model.groups[0].types.size == 1,
            "Ban did not immediately remove type from model");
        require(Vars.control.input.controlGroups[0].size == saved, "Ban mutated native groups");
        fireButton("cg.ban.dagger"); Core.scene.act(.02f);
        require(!hud.profiles.get(UnitTypes.dagger).banned && hud.model.groups[0].types.size == 2, "Unban did not restore model");
        var p = hud.profiles.get(UnitTypes.dagger);
        p.color.set(arc.graphics.Color.red); p.tier = 99; p.order = 99; p.resize = 2; hud.profileChanged(p);
        for (String key : new String[]{"color", "tier", "type", "resize"}) fireButton("cg.reset.unit.dagger." + key);
        require(p.color.equals(p.defaultColor) && p.tier == p.defaultTier && p.order == p.defaultOrder && p.resize == 1,
            "Per-type reset did not restore actual defaults");
    }

    private void verifyGeometryInvalidation() {
        try {
            var members = GroupsHud.class.getDeclaredField("membershipAt"); members.setAccessible(true);
            var states = GroupsHud.class.getDeclaredField("stateAt"); states.setAccessible(true);
            double nextMembers = members.getDouble(hud), nextStates = states.getDouble(hud);
            float gap = hud.settings.groupGap;
            long beforeMembers = hud.model.reconciliations, beforeStates = hud.model.stateUpdates, beforeLayout = hud.layout.rebuilds;
            try {
                members.setDouble(hud, 1e12); states.setDouble(hud, 1e12);
                hud.settings.groupGap = gap + 1; hud.changed(); Core.scene.act(.01f);
                require(hud.layout.rebuilds > beforeLayout && hud.model.reconciliations == beforeMembers &&
                    hud.model.stateUpdates == beforeStates, "Geometry edit triggered unnecessary unit collection");
            } finally {
                members.setDouble(hud, nextMembers); states.setDouble(hud, nextStates);
                hud.settings.groupGap = gap; hud.changed();
            }
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private void verifyBuildingSelection(Rect group) {
        Vars.player.unit(UnitTypes.alpha.spawn(Team.sharded, 600, 600));
        require(!Vars.player.dead() && Vars.player.isBuilder(), "Placement fixture has no living builder");
        Vars.control.input.commandMode = false; Core.scene.act(1f / 60f);
        require(hud.visible, "Building HUD did not appear when enabled");
        Vars.control.input.block = Blocks.copperWall;
        int plans = Vars.player.unit().plans.size;
        nativeSceneClick(hud.x + group.x + 1, hud.y + group.y + 1, true);
        require(Vars.control.input.selectedUnits.size == 2 && Vars.control.input.commandMode,
            "Building click did not select and enter command mode");
        require(Vars.control.input.block == null && Vars.player.unit().plans.size == plans,
            "Building click also placed or queued a block");
        require(!Core.settings.getBool("commandmodehold"), "HUD changed the native command preference");
    }

    /** Move only the fixture's SDL coordinates, never the operating-system cursor. */
    private void nativeSceneClick(float stageX, float stageY, boolean updateInput) {
        try {
            var fx = Core.input.getClass().getDeclaredField("mouseX"); fx.setAccessible(true);
            var fy = Core.input.getClass().getDeclaredField("mouseY"); fy.setAccessible(true);
            int oldX = fx.getInt(Core.input), oldY = fy.getInt(Core.input);
            Vec2 screen = Core.scene.getViewport().project(new Vec2(stageX, stageY));
            int sx = Math.round(screen.x), sy = Math.round(screen.y);
            try {
                fx.setInt(Core.input, sx); fy.setInt(Core.input, sy);
                Core.scene.mouseMoved(sx, sy); Core.scene.act(1f / 60f);
                require(Core.scene.hasMouse(), "Scene did not recognize the HUD under the mouse");
                Core.input.getKeyboard().keyDown(KeyCode.mouseLeft);
                require(Core.scene.touchDown(sx, sy, 0, KeyCode.mouseLeft), "HUD down event was not consumed");
                if (updateInput) Vars.control.input.update();
                Core.input.getKeyboard().postUpdate(); Core.input.getKeyboard().keyUp(KeyCode.mouseLeft);
                require(Core.scene.touchUp(sx, sy, 0, KeyCode.mouseLeft), "HUD up event was not consumed");
                if (updateInput) Vars.control.input.update();
            } finally {
                Core.input.getKeyboard().keyUp(KeyCode.mouseLeft); Core.input.getKeyboard().postUpdate();
                fx.setInt(Core.input, oldX); fy.setInt(Core.input, oldY);
                Core.scene.mouseMoved(oldX, oldY);
            }
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static void verifyTooltipsAndResets(Seq<Element> controls) {
        for (Element element : controls) {
            if (element instanceof TextField && element.name != null && element.name.startsWith("cg.value."))
                require(Core.scene.find("cg.reset." + element.name.substring("cg.value.".length())) instanceof ImageButton,
                    "Field has no reset: " + element.name);
            for (var listener : element.getListeners()) if (listener instanceof Tooltip tooltip) {
                require(tooltip.getContainer().getBackground() == Styles.black8, "Tooltip differs from native translucent help");
                for (Element child : tooltip.getContainer().getChildren()) if (child instanceof Label label)
                    require(label.color.equals(arc.graphics.Color.lightGray), "Tooltip color differs from native help");
            }
        }
    }

    private static void fireButton(String name) {
        Element actor = Core.scene.find(name); require(actor != null, "Missing button: " + name);
        showElement(actor);
        Vec2 point = actor.localToStageCoordinates(new Vec2(actor.getWidth() / 2, actor.getHeight() / 2));
        Element hit = Core.scene.hit(point.x, point.y, true);
        // A narrow HUD's resize edge may cross the middle of its wider editor header.
        for (float fraction : new float[]{.1f, .9f, .25f, .75f}) {
            if (hit != null && hit.isDescendantOf(actor)) break;
            point = actor.localToStageCoordinates(new Vec2(actor.getWidth() * fraction, actor.getHeight() / 2));
            hit = Core.scene.hit(point.x, point.y, true);
        }
        require(hit != null && hit.isDescendantOf(actor), "Button is not reachable: " + name + " hit=" + hit);
        fire(actor, InputEvent.InputEventType.touchDown, point.x, point.y);
        fire(actor, InputEvent.InputEventType.touchUp, point.x, point.y);
    }

    private static void showElement(Element actor) {
        for (int i = 0; i < 12; i++) Core.scene.act(.02f);
        for (int pass = 0; pass < 3; pass++) {
            Core.scene.draw();
            for (Element parent = actor.parent; parent != null; parent = parent.parent) if (parent instanceof ScrollPane scroll) {
                scroll.validate();
                Vec2 location = actor.localToAscendantCoordinates(scroll.getWidget(), new Vec2());
                scroll.scrollTo(location.x, location.y, actor.getWidth(), actor.getHeight(), true, true);
                scroll.updateVisualScroll();
            }
        }
        // Arc updates the scrolled widget's position during draw(), not during validate().
        Core.scene.draw();
    }

    private static void collect(Element element, Seq<Element> result) {
        result.add(element);
        if (element instanceof Group group) for (Element child : group.getChildren()) collect(child, result);
    }

    private static void fire(Element actor, InputEvent.InputEventType type, float stageX, float stageY) {
        InputEvent event = new InputEvent(); event.type = type;
        event.stageX = stageX; event.stageY = stageY; event.pointer = 0; event.keyCode = KeyCode.mouseLeft;
        actor.fire(event);
    }

    private void click(float x, float y) {
        for (InputEvent.InputEventType type : new InputEvent.InputEventType[]{InputEvent.InputEventType.touchDown, InputEvent.InputEventType.touchUp}) {
            fire(hud, type, hud.x + x, hud.y + y);
        }
    }

    private static void hideDialogs() {
        var children = Core.scene.root.getChildren();
        for (int i = children.size - 1; i >= 0; i--) if (children.get(i) instanceof Dialog dialog) dialog.hide(null);
    }

    private void screenshot(String name) {
        ScreenUtils.saveScreenshot(reports.child(name));
    }

    private void report() {
        Arrays.sort(baseline); Arrays.sort(enabled);
        reports.child("result.txt").writeString(String.format(Locale.ROOT,
            "Mindustry %s, %s%nActual hidden SDL/OpenGL client, 1000 initially spawned units; no movement/combat orders, physics enabled.%n" +
            "Baseline update+render median/p95: %.3f/%.3f ms%nHUD update+render median/p95: %.3f/%.3f ms%n" +
            "Selection: group single/double, type, projection PASS%nHide/resume without hidden scans: PASS%n" +
            "Editor move/clamp, edge resize, field focus/keyboard input/reset: PASS%n" +
            "Building selection/native input/block placement prevention: PASS%n" +
            "Telemetry off/on/off, reset buttons and native translucent tooltips: PASS%n" +
            "Editor visual controls, fixed toolbar, hierarchy, columns and complete yellow settings border: PASS%n" +
            "Geometry edits reuse game data; detailed icon threshold/fallback: PASS%n" +
            "Internal projection/OpenGL and actual 640x480/320x240 windows: PASS%n" +
            "All unit rows, avatar bans, ban/unban and per-type resets: PASS%n" +
            "Unit color field width/tint: PASS%nWorld reset: PASS%nRenderer state restore: PASS%n" +
            "This is an automated sandbox run, not a multiplayer or interactive gameplay test.%n",
            Version.buildString(), Core.graphics.getGLVersion(), baseline[120] / 1e6, baseline[228] / 1e6,
            enabled[120] / 1e6, enabled[228] / 1e6));
        System.out.println(reports.child("result.txt").readString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
