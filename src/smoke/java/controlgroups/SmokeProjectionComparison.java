package controlgroups;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.graphics.Texture;
import arc.graphics.g2d.Batch;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mat;
import arc.util.ScreenUtils;
import arc.util.Time;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/** Native color/layer oracle in the disposable test world, never in the production HUD. */
public final class SmokeProjectionComparison {
    private static final int SIZE = 768;

    public static void run(Fi reports, UnitProfiles profiles) {
        FrameBuffer buffer = new FrameBuffer(SIZE, SIZE);
        UnitProjection projection = new UnitProjection(profiles);
        Trace trace = new Trace();
        Batch originalBatch = Core.batch;
        Mat originalProjection = new Mat(Draw.proj()), originalTransform = new Mat(Draw.trans());
        float time = Time.time, cameraX = Core.camera.position.x, cameraY = Core.camera.position.y;
        float cameraW = Core.camera.width, cameraH = Core.camera.height;
        float color = Draw.getColorPacked(), mix = Draw.getMixColorPacked(), stroke = Lines.getStroke(), z = Draw.z();
        float xs = Draw.xscl, ys = Draw.yscl;
        var blend = Draw.getBlend(); var shader = Draw.getShader();
        StringBuilder result = new StringBuilder("type,state,mode,opaque,red,yellow,common-pixels,mean-rgb-error-vs-sorted\n");
        StringBuilder commands = new StringBuilder("type,state,mode,region,tint,mix,blend,z\n");
        int checked = 0;
        Draw.flush();
        try {
            Time.time = 3.1415927f;
            for (UnitType type : new UnitType[]{UnitTypes.conquer, UnitTypes.oct, UnitTypes.reign, UnitTypes.disrupt, UnitTypes.eclipse}) {
                Unit unit = type.spawn(Team.sharded, 450, 450);
                try {
                    Core.camera.position.set(unit); Core.camera.width = Core.camera.height = SIZE / 3f; Core.camera.update();
                    for (int state = 0; state < 7; state++) {
                        pose(unit, state);
                        Pixmap[] images = new Pixmap[3];
                        List<Stamp> nativeCommands = null;
                        int left = SIZE, bottom = SIZE, right = -1, top = -1;
                        try {
                            for (int mode = 0; mode < 3; mode++) {
                                unit.shadowAlpha = 1;
                                Core.batch = trace; trace.prefix = type.name + ',' + state + ',' + (mode == 2 ? "projection" : "native");
                                trace.output = commands; trace.commands.clear();
                                if (mode == 2) projection.draw(unit, 0, SIZE / 2f, SIZE / 2f, type.hitSize * 3, 0, type.hitSize, 10, true, 1);
                                else if (mode == 0) type.draw(unit);
                                if (mode == 0) nativeCommands = new ArrayList<>(trace.commands);
                                if (mode == 2) checked += verifyCommands(nativeCommands, trace.commands, 1, unit, type.name + '/' + state);
                                Core.batch = originalBatch;
                                buffer.begin(Color.clear);
                                try {
                                    Draw.trans(new Mat()); Draw.reset(); Draw.scl(1);
                                    if (mode == 2) Draw.proj(0, 0, SIZE, SIZE);
                                    else Draw.proj(unit.x - SIZE / 6f, unit.y - SIZE / 6f, SIZE / 3f, SIZE / 3f);
                                    Draw.sort(mode == 0);
                                    if (mode == 2) projection.draw(unit, 0, SIZE / 2f, SIZE / 2f, type.hitSize * 3, 0, type.hitSize, 10, true, 1);
                                    else type.draw(unit);
                                    if (mode == 0) {
                                        // Renderer queues normal overlay/space callbacks after units. Without a tail
                                        // request, Arc flushRequests restores normal BEFORE its final additive buffer
                                        // is submitted, changing the isolated reference's actual pixel colors.
                                        Draw.blend(Blending.normal); Draw.draw(mindustry.graphics.Layer.end, () -> { });
                                    }
                                    Draw.flush(); Draw.sort(false);
                                    images[mode] = ScreenUtils.getFrameBufferPixmap(0, 0, SIZE, SIZE);
                                } finally { Draw.sort(false); buffer.end(); }
                            }
                            for (int mode = 0; mode < 3; mode++) {
                                int opaque = 0, red = 0, yellow = 0, common = 0; long error = 0;
                                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                                    int pixel = images[mode].get(x, y), reference = images[0].get(x, y);
                                    int r = pixel >>> 24, g = pixel >>> 16 & 255, b = pixel >>> 8 & 255;
                                    if ((pixel & 255) != 0) {
                                        left = Math.min(left, x); right = Math.max(right, x); bottom = Math.min(bottom, y); top = Math.max(top, y);
                                    }
                                    if ((pixel & 255) > 127) {
                                        opaque++;
                                        if (r > 80 && r > 1.6f * g && r > 1.6f * b) red++;
                                        if (r > 100 && g > 80 && g > 1.4f * b && r < 2 * g) yellow++;
                                        if ((reference & 255) > 127) {
                                            common++;
                                            error += Math.abs(r - (reference >>> 24)) + Math.abs(g - (reference >>> 16 & 255)) + Math.abs(b - (reference >>> 8 & 255));
                                        }
                                    }
                                }
                                require(opaque > 0, "Empty colour reference: " + type.name + '/' + state + '/' + mode);
                                double meanError = common == 0 ? 0 : error / (3d * common);
                                if (mode == 2 && state < 6) {
                                    if (type == UnitTypes.conquer) require(meanError < 6, "Conquer pixel composition diverged: " + state + '/' + meanError);
                                    if (type == UnitTypes.oct || type == UnitTypes.eclipse)
                                        require(meanError < 1, "Body color diverged: " + type.name + '/' + state + '/' + meanError);
                                }
                                result.append(String.format(Locale.ROOT, "%s,%d,%s,%d,%d,%d,%d,%.3f%n", type.name, state,
                                    mode == 0 ? "native-sorted" : mode == 1 ? "native-immediate" : "projection", opaque, red, yellow, common,
                                    meanError));
                            }
                            if (state == 0 || state == 2 || state == 4) {
                                left = Math.max(0, left - 12); bottom = Math.max(0, bottom - 12);
                                right = Math.min(SIZE - 1, right + 12); top = Math.min(SIZE - 1, top + 12);
                                int width = right - left + 1, height = top - bottom + 1;
                                Pixmap comparison = new Pixmap(width * 3, height);
                                try {
                                    for (int mode = 0; mode < 3; mode++) comparison.draw(images[mode], left, bottom, width, height, mode * width, 0, width, height);
                                    PixmapIO.writePng(reports.child("colors-" + type.name + "-" + state + ".png"), comparison);
                                } finally { comparison.dispose(); }
                            }
                        } finally { for (Pixmap image : images) if (image != null) image.dispose(); }
                    }
                    // Live colors may change without rebuilding metadata; use the same prepared plan.
                    pose(unit, 1); unit.team = Team.crux; unit.hitTime = .25f; unit.healTime = .7f;
                    Core.batch = trace; trace.output = new StringBuilder(); trace.prefix = type.name + ",alpha";
                    trace.commands.clear(); type.draw(unit); List<Stamp> reference = new ArrayList<>(trace.commands);
                    trace.commands.clear();
                    projection.draw(unit, 0, 0, 0, type.hitSize, 0, type.hitSize, 0, true, .32f);
                    checked += verifyCommands(reference, trace.commands, .32f, unit, type.name + "/alpha-team");
                    Core.batch = originalBatch;
                } finally { unit.remove(); }
            }
            require(Core.gl.glGetError() == 0, "OpenGL error in native/projection comparison");
        } finally {
            Core.batch = originalBatch; Draw.flush(); Draw.sort(false);
            Draw.proj(originalProjection); Draw.trans(originalTransform); Draw.shader(shader); Draw.blend(blend);
            Draw.color(color); Draw.mixcol(mix); Draw.scl(xs, ys); Draw.z(z); Lines.stroke(stroke);
            Core.camera.position.set(cameraX, cameraY); Core.camera.width = cameraW; Core.camera.height = cameraH; Core.camera.update();
            Time.time = time; buffer.dispose(); trace.dispose();
        }
        reports.child("projection-colors.csv").writeString(result.toString());
        reports.child("projection-color-commands.csv").writeString(commands.toString());
        reports.child("projection-color-parity.txt").writeString(checked + " source-native sprite operations: stable layer order, RGB/alpha/mix/blend PASS.\n" +
            "Five real types, seven states, two teams, HUD alpha 1 and .32. Engine geometry and optional status indicators intentionally unchanged.\n");
    }

    private static void pose(Unit unit, int state) {
        unit.rotation = 90; unit.elevation = unit.type.flying ? 1 : 0; unit.vel.setZero();
        unit.health = unit.maxHealth; unit.hitTime = state == 3 ? .75f : 0; unit.healTime = state == 4 ? .8f : 0;
        unit.drownTime = state == 5 ? .65f : 0; unit.lastDrownFloor = (mindustry.world.blocks.environment.Floor)Blocks.deepwater;
        unit.shield = state == 6 ? 100 : 0; unit.shieldAlpha = state == 6 ? 1 : 0;
        for (var mount : unit.mounts) {
            float value = state == 0 ? 0 : state == 1 ? .5f : 1;
            mount.rotation = 0; mount.warmup = mount.heat = mount.charge = mount.recoil = mount.smoothReload = value;
            mount.reload = value * mount.weapon.reload;
            if (mount.recoils != null) java.util.Arrays.fill(mount.recoils, value);
        }
    }

    private static final class Trace extends SpriteBatch {
        StringBuilder output;
        String prefix;
        final List<Stamp> commands = new ArrayList<>();
        Trace() { super(0); }
        @Override protected void draw(TextureRegion region, float x, float y, float ox, float oy, float w, float h, float rotation) {
            commands.add(new Stamp(region, Draw.getColorPacked(), Draw.getMixColorPacked(), Draw.getBlend(), Draw.z()));
            output.append(prefix).append(',').append(region instanceof AtlasRegion named ? named.name : "quad")
                .append(',').append(Draw.getColor()).append(',').append(Draw.getMixColor())
                .append(',').append(Draw.getBlend() == Blending.additive ? "additive" : "normal").append(',').append(Draw.z()).append('\n');
        }
        @Override protected void draw(Texture texture, float[] vertices, int offset, int count) { }
        @Override protected void flush() { }
    }

    private record Stamp(TextureRegion region, float color, float mix, Blending blend, float z) { }

    private static int verifyCommands(List<Stamp> nativeCommands, List<Stamp> projection, float alpha, Unit unit, String context) {
        var regions = new HashSet<TextureRegion>();
        for (Stamp command : projection) regions.add(command.region);
        var expected = new ArrayList<Stamp>();
        float engineZ = unit.type.engineLayer > 0 ? unit.type.engineLayer : unit.elevation > .5f ? unit.type.flyingLayer :
            unit.type.groundLayer + Math.max(0, Math.min(.01f, unit.type.hitSize / 4000f));
        for (Stamp command : nativeCommands) if (regions.contains(command.region)) {
            // SuppressionFieldAbility also draws the shared circle texture, on its own effect layer.
            // Keep native abilities in the pixel comparison, exclude them from supported-engine command parity.
            if (command.region instanceof AtlasRegion named && named.name.equals("circle") && command.z != engineZ) continue;
            expected.add(command);
        }
        expected.sort(Comparator.comparingInt(command -> Float.floatToRawIntBits(command.z + 16f)));
        require(expected.size() == projection.size(), context + ": sprite count " + expected.size() + " vs " + projection.size());
        Color a = new Color(), b = new Color();
        for (int i = 0; i < expected.size(); i++) {
            Stamp source = expected.get(i), actual = projection.get(i);
            String label = context + '/' + i + '/' + (actual.region instanceof AtlasRegion named ? named.name : "quad");
            require(source.region == actual.region, label + ": incorrect stable layer order, expected " + source.region);
            require(source.blend == actual.blend, label + ": blending differs");
            a.abgr8888(source.color); b.abgr8888(actual.color);
            require(Math.abs(a.r - b.r) < .009 && Math.abs(a.g - b.g) < .009 && Math.abs(a.b - b.b) < .009 &&
                Math.abs(a.a * alpha - b.a) < .009, label + ": color " + a + " vs " + b);
            a.abgr8888(source.mix); b.abgr8888(actual.mix);
            // RGB is immaterial when the mix is fully transparent; native reset uses zero, applyColor may use white.
            require(Math.abs(a.a - b.a) < .009 && (a.a < .009 && b.a < .009 ||
                Math.abs(a.r - b.r) < .009 && Math.abs(a.g - b.g) < .009 && Math.abs(a.b - b.b) < .009),
                label + ": mix " + a + " vs " + b);
        }
        return expected.size();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
