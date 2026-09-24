"""Summarise rotated-order perf runs (tools/perf_run.sh): generation chunks/s and survey seconds."""
import glob, os, re, statistics
LAB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
names = {'A': 'old cache lookup + published Dendry', 'B': 'cache fix + published Dendry',
         'C': 'cache fix + per-seed Dendry', 'D': 'chunk-scoped cache off (published Dendry)'}
gen, sur = {}, {}
for d in sorted(glob.glob(os.path.join(LAB, 'data', 'det', 'perf?_?'))):
    cfg = os.path.basename(d)[4]
    ch = secs = 0
    for l in open(os.path.join(d, 'timing.txt')):
        m = re.search(r'chunks (\d+) seconds ([\d.]+)', l); ch += int(m.group(1)); secs += float(m.group(2))
    gen.setdefault(cfg, []).append(ch / secs)
    for l in open(os.path.join(d, 'survey.txt')):
        m = re.search(r'Survey \d+x\d+ in ([\d.]+)s', l); sur.setdefault(cfg, []).append(float(m.group(1)))
gA, sA = statistics.mean(gen['A']), statistics.mean(sur['A'])
print("| config | generation chunks/s (3 runs) | mean | vs A | biome survey 1024x1024 s (3 runs) | mean | vs A |")
print("|---|---|---|---|---|---|---|")
for c in 'ABCD':
    g, s = gen[c], sur[c]
    print(f"| {c}: {names[c]} | {', '.join(f'{v:.1f}' for v in g)} | {statistics.mean(g):.1f} | "
          f"{100 * (statistics.mean(g) / gA - 1):+.1f}% | {', '.join(f'{v:.1f}' for v in s)} | {statistics.mean(s):.1f} | "
          f"{100 * (statistics.mean(s) / sA - 1):+.1f}% |")
