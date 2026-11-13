package controlgroups;

import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.input.KeyCode;
import arc.math.geom.Rect;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.ui.Dialog;
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
                    screenshot("02-editor.png"); hud.openUnits(); stage++; frames = 0;
                }
                case 3 -> {
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
        hud = (GroupsHud)actor;
        hud.settings.vertical = true; hud.settings.reverse = false; hud.settings.projection = true;
        hud.settings.collapse = false; hud.settings.xFraction = 0; hud.settings.yFraction = 0.5f;
        hud.settings.length = 640; hud.settings.breadth = 240; hud.settings.advanced = false;
        Core.settings.put("fpscap", 0);
        Core.settings.put("effects", false);
        Core.settings.put("commandmodehold", false);
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
        hud.draw();
        require(Draw.getColorPacked() == color && Draw.getMixColorPacked() == mix, "HUD leaked draw state");
    }

    private void click(float x, float y) {
        for (InputEvent.InputEventType type : new InputEvent.InputEventType[]{InputEvent.InputEventType.touchDown, InputEvent.InputEventType.touchUp}) {
            InputEvent event = new InputEvent(); event.type = type;
            event.stageX = hud.x + x; event.stageY = hud.y + y;
            event.pointer = 0; event.keyCode = KeyCode.mouseLeft;
            hud.fire(event);
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
            "Selection: group single/double, type, projection PASS%nWorld reset: PASS%nDraw color/mix restore: PASS%n" +
            "This is an automated sandbox run, not a multiplayer or interactive gameplay test.%n",
            Version.buildString(), Core.graphics.getGLVersion(), baseline[120] / 1e6, baseline[228] / 1e6,
            enabled[120] / 1e6, enabled[228] / 1e6));
        System.out.println(reports.child("result.txt").readString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
