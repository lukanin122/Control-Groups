package controlgroups;

import arc.Core;
import arc.files.Fi;
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
                    verifyEditor(); screenshot("02-editor.png"); hud.openUnits(); stage++; frames = 0;
                }
                case 3 -> {
                    verifyUnitFields();
                    screenshot("03-unit-settings.png");
                    hud.toggleEditor(); hud.settings.collapse = true; hud.changed(); stage++; frames = 0;
                }
                case 4 -> {
                    screenshot("04-collapsed.png"); hud.settings.vertical = false; hud.settings.collapse = false;
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
                    Vars.logic.reset(); require(hud.model.observedUnits() == 0, "World reset did not clear observations");
                    report(); Core.app.exit();
                }
                default -> throw new AssertionError("Unknown stage");
            }
        } catch (Throwable exception) {
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
        for (String icon : new String[]{"flipX", "flipY", "eyeOff", "move", "settings", "list"}) {
            require(mindustry.gen.Icon.icons.containsKey(icon), "Missing editor icon: " + icon);
        }
        hud.settings.vertical = true; hud.settings.reverse = false; hud.settings.projection = true;
        hud.settings.collapse = false; hud.settings.xFraction = 0; hud.settings.yFraction = 0.5f;
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
        long updates = hud.model.reconciliations;
        Vars.control.input.commandMode = false; Core.scene.act(1f / 60f);
        require(!hud.visible && hud.model.reconciliations == updates, "Hidden HUD kept scanning units");
        Vars.control.input.commandMode = true; Core.scene.act(1f / 60f);
        require(hud.visible && hud.model.reconciliations > updates, "HUD did not resume after command mode was restored");
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

    private void verifyEditor() {
        require(hud.editing, "Editor did not open");
        Element editor = Core.scene.find("control-groups-editor");
        Seq<Element> controls = new Seq<>(); collect(editor, controls);
        ScrollPane settingsScroll = null;
        for (Element element : controls) if (element instanceof ScrollPane scroll) {
            require(scroll.getWidget().getWidth() <= scroll.getScrollWidth() + 0.01f,
                "Editor content exceeds the horizontal viewport: " + scroll.getWidget().getWidth() + "/" + scroll.getScrollWidth());
            settingsScroll = scroll;
        }
        require(settingsScroll != null, "Editor scroll pane is missing");
        float oldScroll = settingsScroll.getScrollY();
        for (Element element : controls) if (element instanceof CheckBox check) {
            require(check.getWidth() <= editor.getWidth() && check.getLabel().getWidth() > 0, "Editor label overflow");
        }
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

    private void verifyUnitFields() {
        Element panel = Core.scene.find("control-groups-unit-settings");
        require(panel != null && panel.visible, "Unit settings did not open");
        Seq<Element> controls = new Seq<>(); collect(panel, controls);
        int colors = 0;
        GlyphLayout glyph = new GlyphLayout();
        for (Element element : controls) if (element instanceof TextField field && field.getText().startsWith("#")) {
            glyph.setText(field.getStyle().font, "#FFFFFF");
            var background = field.getStyle().background;
            float padding = background == null ? 0 : background.getLeftWidth() + background.getRightWidth();
            require(field.getWidth() >= glyph.width + padding + 2, "Color field does not fit seven characters");
            colors++;
        }
        require(colors > 0, "No unit color controls were built");
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
            "Mindustry %s, %s%nActual hidden SDL/OpenGL client, 1000 stationary units.%n" +
            "Baseline update+render median/p95: %.3f/%.3f ms%nHUD update+render median/p95: %.3f/%.3f ms%n" +
            "Selection: group single/double, type, projection PASS%nHide/resume without hidden scans: PASS%n" +
            "Editor move/clamp, edge resize and label bounds: PASS%nUnit color field width: PASS%nWorld reset: PASS%nRenderer state restore: PASS%n" +
            "This is an automated sandbox run, not a multiplayer or interactive gameplay test.%n",
            Version.buildString(), Core.graphics.getGLVersion(), baseline[120] / 1e6, baseline[228] / 1e6,
            enabled[120] / 1e6, enabled[228] / 1e6));
        System.out.println(reports.child("result.txt").readString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
