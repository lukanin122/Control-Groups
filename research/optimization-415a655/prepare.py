"""Reproduce an isolated prototype build; never edits production files or Git state."""
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASE = '415a6552eb0cc3d9cdff4a59d164b98f98049a47'
SCRATCH = ROOT / '.gradle/optimization-research-415a655'
SNAP = SCRATCH / 'snapshot'
SCRATCH.mkdir(parents=True, exist_ok=True)
subprocess.run(['git', 'archive', BASE, '-o', str(SCRATCH / 'source.zip')], cwd=ROOT, check=True)
with zipfile.ZipFile(SCRATCH / 'source.zip') as archive:
    archive.extractall(SNAP)

main = SNAP / 'src/main/java/controlgroups'
stock = (main / 'UnitProjection.java').read_text(encoding='utf-8')
(main / 'UnitProjection.java').write_text(stock.replace('public final class UnitProjection', 'public class UnitProjection'), encoding='utf-8')
prototype = stock.replace('public final class UnitProjection', 'public final class ResearchProjection extends UnitProjection')
prototype = prototype.replace('public UnitProjection(UnitProfiles profiles) {', 'public ResearchProjection(UnitProfiles profiles) {\n        super(profiles);')
prototype = prototype.replace('    public void clear() {', '''    public int variant;
    private long epoch, preparedEpoch = -1;
    private Unit lastUnit;
    private boolean lastAdvanced;
    private float lastAlpha;
    public void beginFrame() { epoch++; }

    public void clear() {''')
prototype = prototype.replace('''        tint(unit); unitColor = Draw.getColorPacked(); unitMix = Draw.getMixColorPacked();''', '''        if ((variant & 4) != 0 && preparedEpoch == epoch && lastUnit == unit && lastAdvanced == advanced && lastAlpha == alpha) return;
        preparedEpoch = epoch; lastUnit = unit; lastAdvanced = advanced; lastAlpha = alpha;
        tint(unit); unitColor = Draw.getColorPacked(); unitMix = Draw.getMixColorPacked();''')
prototype = prototype.replace('''                    SideState side = frame.sides[op.side];''', '''                    SideState side = frame.sides[op.side];
                    if ((variant & 1) != 0) {
                        float packed = op.kind == ProjectionPlan.Kind.PART_HEAT ? side.heatColor :
                            op.kind == ProjectionPlan.Kind.PART_OUTLINE ? side.outlineColor : side.color;
                        if ((Float.floatToRawIntBits(packed) >>> 24) == 0 || side.sx == 0 || side.sy == 0) continue;
                    }''')
prototype = prototype.replace('''        if (Draw.getBlend() != desired) { Draw.flush(); Draw.blend(desired); }''', '''        if (Draw.getBlend() != desired) {
            if (!(arc.Core.batch instanceof ResearchBatch.Unified)) Draw.flush();
            Draw.blend(desired);
        }''')
# A cheap pure-Java candidate: interpolate identical mirror colors once, not once per side.
prototype = prototype.replace('''        float color = unitColor, mix = unitMix;
        for''', '''        float color = unitColor, mix = unitMix;
        float ownColor = 0, ownMix = 0;
        if ((variant & 2) != 0) {
            if (part.color != null) { partColor.set(part.color); if (part.colorTo != null) partColor.lerp(part.colorTo, progress); ownColor = packed(partColor, alpha); }
            if (part.mixColor != null) { partMix.set(part.mixColor); if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress); ownMix = packed(partMix, 1); }
        }
        for''')
prototype = prototype.replace('''                    color = packed(partColor, alpha);''', '''                    color = (variant & 2) != 0 ? ownColor : packed(partColor, alpha);''')
prototype = prototype.replace('''                    mix = packed(partMix, 1);''', '''                    mix = (variant & 2) != 0 ? ownMix : packed(partMix, 1);''')
prototype = prototype.replace('''                    partColor.set(part.color); if (part.colorTo != null) partColor.lerp(part.colorTo, progress);''', '''                    if ((variant & 2) == 0) { partColor.set(part.color); if (part.colorTo != null) partColor.lerp(part.colorTo, progress); }''')
prototype = prototype.replace('''                    partMix.set(part.mixColor); if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress);''', '''                    if ((variant & 2) == 0) { partMix.set(part.mixColor); if (part.mixColorTo != null) partMix.lerp(part.mixColorTo, progress); }''')
(main / 'ResearchProjection.java').write_text('// GENERATED isolated experiment from ' + BASE + '\n' + prototype, encoding='utf-8')
for source in HERE.glob('*.java'):
    target = SNAP / ('src/smoke/java/controlgroups' if source.name == 'ResearchLauncher.java' else 'src/main/java/controlgroups') / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)

# Local clip experiments only. The exact baseline remains available with both flags false.
p = main / 'GroupsHud.java'
s = p.read_text(encoding='utf-8')
s = s.replace('''            Draw.flush();
            if (clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {''', '''            boolean skipClip = ResearchBatch.skipClip(this, box);
            if (!ResearchBatch.dropEmptyClipFlush && !skipClip) Draw.flush();
            if (skipClip || clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {''')
s = s.replace('''                } finally { Draw.flush(); clipEnd(); }''', '''                } finally { if (!skipClip) { if (!ResearchBatch.dropEmptyClipFlush) Draw.flush(); clipEnd(); } }''')
s = s.replace('''    @Override
    public void draw() {''', '''    @Override
    public void draw() {
        if (ResearchBatch.suppressHud) { ResearchBatch.hudNanos = 0; return; }
        long started = ResearchBatch.measureHud ? System.nanoTime() : 0;
        arc.graphics.g2d.Batch previous = ResearchBatch.beginHud();
        try { drawResearchOriginal(); }
        finally {
            ResearchBatch.endHud(previous);
            if (ResearchBatch.measureHud) ResearchBatch.hudNanos = System.nanoTime() - started;
        }
    }

    private void drawResearchOriginal() {''')
p.write_text(s, encoding='utf-8')
with (SNAP / 'build.gradle').open('a', encoding='utf-8') as f:
    f.write('''
tasks.register('optimizationResearch', JavaExec) {
    dependsOn jar, smokeClasses
    classpath = sourceSets.smoke.runtimeClasspath
    mainClass = 'controlgroups.ResearchLauncher'
    workingDir = layout.projectDirectory.dir('.gradle/research-run').asFile
    executable = project.property('mindustryJava')
    def data = new File(workingDir, 'data-' + System.currentTimeMillis())
    systemProperty 'mindustry.data.dir', data.absolutePath
    systemProperty 'nodiscord', 'true'
    systemProperty 'research.frameOnly', project.findProperty('researchFrameOnly') ?: 'false'
    systemProperty 'research.proofOnly', project.findProperty('researchProofOnly') ?: 'false'
    args project.property('researchReports')
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
