"""Stage A summaries, preserving separate block quantiles and raw failed hypotheses."""
from pathlib import Path
from collections import defaultdict
import csv
import json

HERE = Path(__file__).resolve().parent
text = ['# Stage A measurement tables', '',
        'Microseconds unless explicitly nanoseconds. Ranges are separate block values, '
        'not pooled percentiles or confidence intervals. See README for fixture versions.', '']
result = {}


def read(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream))


def table(headers, rows):
    text.append('| ' + ' | '.join(headers) + ' |')
    text.append('| ' + ' | '.join(['---'] * len(headers)) + ' |')
    text.extend('| ' + ' | '.join(map(str, row)) + ' |' for row in rows)
    text.append('')


def span(rows, key):
    values = [float(r[key]) for r in rows]
    return f'{min(values):.3f}..{max(values):.3f}'


for name in ('screening-1', 'final-1', 'final-2', 'clipping-1', 'native-1'):
    run = HERE / name
    if not (run / 'pixels.csv').exists() or (run / 'FAILED.txt').exists():
        continue
    text += [f'## {name}', '']
    parity = defaultdict(list)
    for row in read(run / 'pixels.csv'):
        scope = row['scope']
        key = ('parent-clip' if scope.startswith('parent-clip-') else scope, row['variant'])
        parity[key].append(row)
    quality = []
    for (scope, variant), rows in sorted(parity.items()):
        quality.append(dict(scope=scope, variant=variant, cases=len(rows),
                            different=sum(int(r['changed']) != 0 for r in rows),
                            max_rgb=max(int(r['max-rgb']) for r in rows),
                            max_alpha=max(int(r['max-alpha']) for r in rows)))
    table(['Scope', 'Variant', 'Cases', 'Different', 'Max RGB / alpha'],
          [(r['scope'], r['variant'], r['cases'], r['different'], f"{r['max_rgb']} / {r['max_alpha']}") for r in quality])
    result[name] = dict(quality=quality)
    times = read(run / 'times.csv')
    hud = defaultdict(list)
    for row in times:
        if row['scope'].startswith('HUD-'):
            hud[(row['scope'], row['type'], row['state'], row['variant'])].append(row)
    text += ['### Full HUD ABBA blocks', '']
    table(['Window', 'Config', 'Heat', 'Variant', 'p50 blocks', 'p95 blocks', 'p99 blocks',
           'Completed blocks', 'Min / max individual packet', 'B/packet blocks'],
          [(w, c, h, v, span(rows, 'p50-us'), span(rows, 'p95-us'), span(rows, 'p99-us'),
            span(rows, 'completed-us'), f"{min(float(r['min-us']) for r in rows):.3f} / {max(float(r['max-us']) for r in rows):.3f}",
            span(rows, 'B-per-packet')) for (w, c, h, v), rows in sorted(hud.items())])
    counts = read(run / 'counts.csv')
    text += ['### Actual HUD counters', '']
    table(['Window', 'Config', 'Heat', 'Variant', 'Quads', 'Flush requests', 'Nonempty', 'GL draws', 'Textures', 'Scissor'],
          [(r['scope'], r['type'], r['state'], r['variant'], r['quads'], r['flush-requests'], r['nonempty'],
            r['GL-draws'], r['GL-textures'], r['GL-scissor']) for r in counts if r['scope'].startswith('HUD-')])
    if (run / 'clip-sequences.csv').exists():
        trace = read(run / 'clip-sequences.csv')
        result[name]['ordered_clip_cases'] = len(trace)
        result[name]['ordered_clip_failures'] = sum(r['ordered-draw-state-equal'] != 'true' for r in trace)
        text += [f'Ordered nonempty draw/state comparisons: {len(trace)}, '
                 f'failures: {result[name]["ordered_clip_failures"]}.', '']
    frame = read(run / 'frames.csv')
    if frame:
        text += ['### Sequential client frame phases (limited attribution)', '',
                 '0=draw off, 1=basic baseline, 2=full baseline, 3=full candidate. '
                 'Baseline/candidate projection bodies were identical but separate objects/classes in these earlier phases. '
                 'Do not attribute their timing differences to clipping or convert to FPS.', '']
        table(list(frame[0]), [list(r.values()) for r in frame])
    if (run / 'prepare.csv').exists():
        rows = read(run / 'prepare.csv')
        text += ['### Prepare-only MethodHandle batches (ns/call)', '']
        table(list(rows[0]), [list(r.values()) for r in rows])

(HERE / 'MEASUREMENTS.md').write_text('\n'.join(text), encoding='utf-8')
(HERE / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Wrote Stage A MEASUREMENTS.md and summary.json')
