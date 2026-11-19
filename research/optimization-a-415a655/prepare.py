"""Stage A only. Reuses the previous SDL/counting design, never patches production files."""
from pathlib import Path
import subprocess
import zipfile
import shutil

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASE = '415a6552eb0cc3d9cdff4a59d164b98f98049a47'
WORK = ROOT / '.gradle/optimization-a-415a655'
SNAP = WORK / 'snapshot'
WORK.mkdir(parents=True, exist_ok=True)
subprocess.run(['git', 'archive', BASE, '-o', str(WORK / 'baseline.zip')], cwd=ROOT, check=True)
with zipfile.ZipFile(WORK / 'baseline.zip') as archive:
    archive.extractall(SNAP)
main = SNAP / 'src/main/java/controlgroups'
before = (main / 'UnitProjection.java').read_text(encoding='utf-8')
before_hud = (main / 'GroupsHud.java').read_text(encoding='utf-8')
for source in (ROOT / 'src/main/java/controlgroups').glob('*.java'):
    shutil.copyfile(source, main / source.name)
current = (main / 'UnitProjection.java').read_text(encoding='utf-8')
assert current == before, 'Stage A timing assumes the production adapter is unchanged'
(main / 'UnitProjection.java').write_text(current.replace('public final class UnitProjection', 'public class UnitProjection'), encoding='utf-8')
expected_e = before_hud.replace('''            Draw.flush();
            if (clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {''', '''            // ScissorStack flushes on successful push/pop; rejected clips retain the original boundary below.
            if (clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {''')
expected_e = expected_e.replace('''                } finally { Draw.flush(); clipEnd(); }''', '''                } finally { clipEnd(); }
            } else {
                Draw.flush();''')
expected_post = before_hud.replace('''                } finally { Draw.flush(); clipEnd(); }''', '''                } finally { clipEnd(); } // ScissorStack.pop flushes before changing the clip.''')
production_hud = (main / 'GroupsHud.java').read_text(encoding='utf-8')
assert production_hud in (before_hud, expected_e, expected_post), 'Unmodelled production HUD change'
keep_leading = production_hud != expected_e


def heat_hoist(source):
    source = source.replace('float heat = found(part.heat) ?', 'boolean hasHeat = found(part.heat);\n        float heat = hasHeat ?')
    source = source.replace('float color = unitColor, mix = unitMix;', 'float color = unitColor, mix = unitMix;\n        float heatColor = packed(part.heatColor, alpha * heat);')
    source = source.replace('pose.heatColor = packed(part.heatColor, alpha * heat);', 'pose.heatColor = heatColor;')
    return source.replace('if (found(part.heat)) color = unitColor;', 'if (hasHeat) color = unitColor;')


def color_hoist(source):
    source = heat_hoist(source)
    source = source.replace('float heatColor = packed(part.heatColor, alpha * heat);', '''float heatColor = packed(part.heatColor, alpha * heat);
        float ownColor = 0, ownMix = 0;
        if (part.color != null) { partColor.set(part.color); if (part.colorTo != null) partColor.lerp(part.colorTo, progress); ownColor = packed(partColor, alpha); }
        if (part.mixColor != null) { partMix.set(part.mixColor); if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress); ownMix = packed(partMix, 1); }''')
    source = source.replace('''                    partColor.set(part.color); if (part.colorTo != null) partColor.lerp(part.colorTo, progress);
                    color = packed(partColor, alpha);''', '                    color = ownColor;')
    return source.replace('''                    partMix.set(part.mixColor); if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress);
                    mix = packed(partMix, 1);''', '                    mix = ownMix;')


def raw_skip(source):
    return source.replace('''                    SideState side = frame.sides[op.side];''', '''                    SideState side = frame.sides[op.side];
                    Blending mode = op.kind == ProjectionPlan.Kind.PART_HEAT ? Blending.additive :
                        op.kind == ProjectionPlan.Kind.PART_OUTLINE ? Blending.normal : plan.parts[op.index].region.blending;
                    float packed = op.kind == ProjectionPlan.Kind.PART_HEAT ? side.heatColor :
                        op.kind == ProjectionPlan.Kind.PART_OUTLINE ? side.outlineColor : side.color;
                    // EXPERIMENT: valid only under the certified stock graphics state in this fixture.
                    if ((mode == Blending.normal || mode == Blending.additive) && (Float.floatToRawIntBits(packed) >>> 24) == 0) continue;''')


for name, source in [('BeforeProjection', before), ('HeatProjection', heat_hoist(before)),
                     ('ColorsProjection', color_hoist(before)), ('RawSkipProjection', raw_skip(before))]:
    source = source.replace('public final class UnitProjection', f'public final class {name} extends UnitProjection')
    source = source.replace('public UnitProjection(UnitProfiles profiles) {', f'public {name}(UnitProfiles profiles) {{\n        super(profiles);')
    (main / (name + '.java')).write_text('// Isolated Stage A candidate, baseline ' + BASE + '\n' + source, encoding='utf-8')

# Same actor/model/layout for both clip variants. A rejected push keeps the original pending boundary.
hud = before_hud.replace('''            Draw.flush();
            if (clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {''', '''            if (StageABatch.baselineClips || StageABatch.keepLeading) Draw.flush();
            if (clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {''')
hud = hud.replace('''                } finally { Draw.flush(); clipEnd(); }''', '''                } finally { if (StageABatch.baselineClips) Draw.flush(); clipEnd(); }
            } else if (!StageABatch.baselineClips && !StageABatch.keepLeading) {
                Draw.flush();''')
assert 'StageABatch.baselineClips' in hud
hud = hud.replace('''    public void draw() {''', '''    public void draw() {
        if (StageABatch.suppressHud) { StageABatch.hudNanos = 0; return; }
        long started = StageABatch.measureHud ? System.nanoTime() : 0;
        try { drawStageA(); }
        finally { if (StageABatch.measureHud) StageABatch.hudNanos = System.nanoTime() - started; }
    }

    private void drawStageA() {''')
(main / 'GroupsHud.java').write_text(hud, encoding='utf-8')

for source in HERE.glob('*.java'):
    target = SNAP / ('src/smoke/java/controlgroups' if source.name == 'StageALauncher.java' else 'src/main/java/controlgroups') / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    if source.name == 'StageABatch.java':
        target.write_text(target.read_text(encoding='utf-8').replace('public static boolean keepLeading=true;',
                          'public static boolean keepLeading=' + str(keep_leading).lower() + ';'), encoding='utf-8')

with (SNAP / 'build.gradle').open('a', encoding='utf-8') as stream:
    stream.write('''
tasks.register('stageA', JavaExec) {
    dependsOn jar, smokeClasses
    classpath = sourceSets.smoke.runtimeClasspath
    mainClass = 'controlgroups.StageALauncher'
    workingDir = layout.projectDirectory.dir('.gradle/stage-a-run').asFile
    executable = project.property('mindustryJava')
    def data = new File(workingDir, 'data-' + System.currentTimeMillis())
    systemProperty 'mindustry.data.dir', data.absolutePath
    systemProperty 'nodiscord', 'true'
    systemProperty 'stageA.mode', project.findProperty('stageAMode') ?: 'all'
    args project.property('stageAReports')
    doFirst {
        workingDir.mkdirs()
        copy { from tasks.jar.archiveFile; into new File(data, 'mods') }
        if (project.hasProperty('buildWheelDirectory')) copy {
            from project.property('buildWheelDirectory'); into new File(data,'mods/BuildWheel')
            exclude '.git/**', 'tests/**'
        }
    }
}
''')
print(SNAP)
