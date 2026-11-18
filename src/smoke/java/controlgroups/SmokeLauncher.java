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
import mindustry.game.EventType.AtlasPackEvent;
import arc.graphics.g2d.TextureRegion;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.LongConsumer;

/** Starts a real client. The scenario must link against Mindustry's child-first mod loader. */
public final class SmokeLauncher extends DesktopLauncher {
    private final Fi reports;
    private boolean loaded;
    private LongConsumer scenario;
    private final java.util.Map<String, float[]> sourceHulls = new java.util.HashMap<>();

    private SmokeLauncher(String[] args) {
        super(new String[0]);
        reports = new Fi(args[0]); reports.mkdirs();
        reports.child("FAILED.txt").delete();
        Events.on(ClientLoadEvent.class, event -> loaded = true);
        Events.on(AtlasPackEvent.class, event -> {
            for (var region : Core.atlas.getRegions()) sourceHulls.put(region.name, visibleHull(region));
        });
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
                scenario = (LongConsumer)Class.forName("controlgroups.SmokeScenario", true, bridge)
                    .getConstructor(Fi.class, java.util.Map.class).newInstance(reports, sourceHulls);
            }
            scenario.accept(elapsed);
        } catch (Throwable exception) {
            reports.child("FAILED.txt").writeString(arc.util.Strings.getStackTrace(exception));
            exception.printStackTrace(); System.exit(2);
        }
    }

    /** Test-only independent oracle, captured while the repacker still owns its pixmaps. */
    public static float[] visibleHull(TextureRegion region) {
        float[] full = {0, 0, 0, 1, 1, 1, 1, 0};
        try {
            if (region.u > region.u2 || region.v > region.v2 || region.texture.getMinFilter().isMipMap()) return full;
            var image = Core.atlas.getPixmap(region);
            arc.struct.FloatSeq points = new arc.struct.FloatSeq();
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (int y = 0; y < image.height; y++) {
                int first = image.width, last = -1;
                for (int x = 0; x < image.width; x++) if ((image.get(x, y) & 255) != 0) {
                    first = Math.min(first, x); last = Math.max(last, x);
                }
                if (last < first) continue;
                int l = Math.max(0, 2 * first - 1), r = Math.min(2 * image.width, 2 * last + 3);
                int b = Math.max(0, 2 * y - 1), t = Math.min(2 * image.height, 2 * y + 3);
                for (int corner = 0; corner < 4; corner++) {
                    int px = (corner & 1) == 0 ? l : r, py = (corner & 2) == 0 ? b : t;
                    if (seen.add(((long)px << 32) | (py & 0xffffffffL)))
                        points.add(px / (2f * image.width), 1 - py / (2f * image.height));
                }
            }
            return points.size == 0 ? new float[0] : new arc.math.geom.ConvexHull().computePolygon(points, false).toArray();
        } catch (RuntimeException unavailable) { return full; }
    }
}
