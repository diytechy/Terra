"""Track the land-land transects that P1 made worse, under other variants.

A transect is 'worsened' when its ravine under <p1 label> exceeds the baseline by >= 4 blocks
(same transect position: rasters are identical across variants). For each other label, reports
how many of those are still >= 4 blocks worse than baseline, and prints the per-transect values.

Usage: python worsened.py <baseline_anyland> <p1_anyland> <label_anyland> [...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import load  # noqa: E402


def index(label):
    return {(r["area"], r["window"], t["x"], t["z"]): t for r in load(label) for t in r["transects"]}


base, p1 = index(sys.argv[1]), index(sys.argv[2])
others = {l: index(l) for l in sys.argv[3:]}
worse = sorted((k for k in p1 if k in base and p1[k]["ravine"] - base[k]["ravine"] >= 4),
               key=lambda k: -(p1[k]["ravine"] - base[k]["ravine"]))
print(f"{len(worse)} transects worse by >= 4 under {sys.argv[2]} (of {len(p1)})")
for l, idx in others.items():
    still = [k for k in worse if k in idx and idx[k]["ravine"] - base[k]["ravine"] >= 4]
    better = [k for k in worse if k in idx and idx[k]["ravine"] <= base[k]["ravine"]]
    print(f"  {l}: still >= 4 worse than baseline: {len(still)}; at or below baseline: {len(better)}")
hdr = "| area/window | x,z | B -> A | baseline | " + sys.argv[2] + " | " + " | ".join(others) + " |"
print(hdr)
print("|" + "---|" * (4 + len(others)))
for k in worse:
    t = p1[k]
    vals = " | ".join(f"{others[l][k]['ravine']:.0f}" if k in others[l] else "-" for l in others)
    print(f"| {k[0]}/{k[1]} | {k[2]},{k[3]} | {t['B']} -> {t['A']} | {base[k]['ravine']:.0f} | {t['ravine']:.0f} | {vals} |")
