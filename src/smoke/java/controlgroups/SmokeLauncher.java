package controlgroups;

import arc.Core;
import arc.Events;
import arc.backend.sdl.SdlApplication;
import arc.backend.sdl.SdlConfig;
import arc.files.Fi;
import arc.scene.Element;
import mindustry.core.Version;
import mindustry.desktop.DesktopLauncher;
import mindustry.game.EventType.ClientLoadEvent;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.LongConsumer;

/** Starts a real client. The scenario must link against Mindustry's child-first mod loader. */
public final class SmokeLauncher extends DesktopLauncher {
    private final Fi reports;
    private boolean loaded;
    private LongConsumer scenario;

    private SmokeLauncher(String[] args) {
        super(new String[0]);
        reports = new Fi(args[0]); reports.mkdirs();
        reports.child("FAILED.txt").delete();
        Events.on(ClientLoadEvent.class, event -> loaded = true);
    }

    public static void main(String[] args) {
        Version.init(); Version.isSteam = false;
        SdlConfig config = new SdlConfig();
        config.title = "Control Groups isolated validation";
        config.width = 1280; config.height = 900;
        config.initialVisible = false; config.disableAudio = true; config.vSyncEnabled = false;
        config.allowGl30 = false; config.glVersions = new int[][]{{2, 1}};
        new SdlApplication(new SmokeLauncher(args), config);
    }

    @Override
    public void update() {
        long start = System.nanoTime();
        try {
            super.update();
            long elapsed = System.nanoTime() - start;
            if (!loaded) return;
            if (scenario == null) {
                Element actor = Core.scene.find("control-groups-hud");
                if (actor == null) {
                    StringBuilder mods = new StringBuilder();
                    for (var mod : mindustry.Vars.mods.list()) mods.append(mod.name).append(" enabled=").append(mod.enabled()).append(" main=").append(mod.main).append('\n');
                    throw new AssertionError("Mod did not attach the HUD. Data=" + mindustry.Vars.dataDirectory + "\n" + mods);
                }
                URL code = getClass().getProtectionDomain().getCodeSource().getLocation();
                ClassLoader bridge = new URLClassLoader(new URL[]{code}, actor.getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (!name.equals("controlgroups.SmokeScenario") && !name.startsWith("controlgroups.SmokeScenario$")) return super.loadClass(name, resolve);
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                };
                scenario = (LongConsumer)Class.forName("controlgroups.SmokeScenario", true, bridge).getConstructor(Fi.class).newInstance(reports);
            }
            scenario.accept(elapsed);
        } catch (Throwable exception) {
            reports.child("FAILED.txt").writeString(arc.util.Strings.getStackTrace(exception));
            exception.printStackTrace(); System.exit(2);
        }
    }
}
