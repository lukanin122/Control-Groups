package controlgroups;

import arc.Core;
import arc.Events;
import arc.backend.sdl.SdlApplication;
import arc.backend.sdl.SdlConfig;
import arc.files.Fi;
import mindustry.core.Version;
import mindustry.desktop.DesktopLauncher;
import mindustry.game.EventType.ClientLoadEvent;
import java.util.function.LongConsumer;

/** Isolated research client; all source changes and data are inside the disposable snapshot. */
public final class ResearchLauncher extends DesktopLauncher {
    private final Fi reports;
    private boolean loaded;
    private LongConsumer research;
    private ResearchLauncher(String[] args) {
        super(new String[0]); reports = new Fi(args[0]); reports.mkdirs(); reports.child("FAILED.txt").delete();
        Events.on(ClientLoadEvent.class, event -> loaded = true);
    }
    public static void main(String[] args) {
        Version.init(); Version.isSteam = false;
        SdlConfig config = new SdlConfig(); config.title = "Control Groups isolated optimization research";
        config.initialVisible = false; config.disableAudio = true; config.width = 1280; config.height = 900;
        config.vSyncEnabled = false; config.allowGl30 = false; config.glVersions = new int[][]{{2,1}};
        new SdlApplication(new ResearchLauncher(args), config);
    }
    @Override public void update() {
        long start = System.nanoTime();
        try {
            super.update(); long elapsed = System.nanoTime() - start;
            if (!loaded) return;
            if (research == null) {
                var actor = Core.scene.find("control-groups-hud");
                if (actor == null) throw new AssertionError("No HUD");
                research = (LongConsumer)Class.forName("controlgroups.OptimizationResearch", true, actor.getClass().getClassLoader())
                    .getConstructor(Fi.class).newInstance(reports);
            }
            research.accept(elapsed);
        } catch (Throwable error) {
            reports.child("FAILED.txt").writeString(arc.util.Strings.getStackTrace(error));
            error.printStackTrace(); System.exit(2);
        }
    }
}
