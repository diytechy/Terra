"""Final comparison tables across labels, same windows, optional excluded windows.

Usage: python final_tables.py --labels a,b,c --areas s1a,... [--exclude area/window,...] [--pair T1,T2]
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import extra, load, stats  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--labels", required=True)
ap.add_argument("--areas", default=None)
ap.add_argument("--exclude", default="")
ap.add_argument("--pair", default=None)
a = ap.parse_args()
labels = a.labels.split(",")
areas = set(a.areas.split(",")) if a.areas else None
excl = set(tuple(x.split("/")) for x in a.exclude.split(",") if x)
data = {l: [r for r in load(l, areas) if (r["area"], r["window"]) not in excl] for l in labels}
common = set.intersection(*[{(r["area"], r["window"]) for r in v} for v in data.values()])
rows = {}
for l, res in data.items():
    res = [r for r in res if (r["area"], r["window"]) in common]
    trs = [t for r in res for t in r["transects"]]
    if a.pair:
        p = sorted(a.pair.split(","))
        trs = [t for t in trs if sorted((t["tmplA"], t["tmplB"])) == p]
    ctl = [t for r in res for t in r["controls"]]
    s, e, c, ec = stats(trs), extra(trs), stats(ctl), extra(ctl)
    orph = np.mean([r["orphans"]["bandEndpointsPer100"] for r in res])
    rows[l] = [s["n"], s["median"], s["p90"], s["max"], 100 * s["fracGE4"], 100 * s["fracGE8"], e["slivers"], e["ledges"],
               e["oddGapsPerTransect"], s["rise4p90"], s["rise4max"], orph, 100 * c["fracGE4"], ec["slivers"], c["rise4p90"]]
names = ["transects", "ravine median", "ravine p90", "ravine max", "ravine >=4 (%)", "ravine >=8 (%)", "slivers/transect",
         "1-block ledges/transect", "odd step gaps/transect", "rise over 4 blocks p90", "rise over 4 blocks max",
         "abrupt edge ends /100 border blocks", "controls: ravine >=4 (%)", "controls: slivers/transect", "controls: rise4 p90"]
print(f"windows: {len(common)}" + (f", pair {a.pair}" if a.pair else ""))
print("| metric | " + " | ".join(labels) + " |")
print("|---|" + "---|" * len(labels))
for i, n in enumerate(names):
    print(f"| {n} | " + " | ".join(f"{rows[l][i]:.2f}" if isinstance(rows[l][i], float) else str(rows[l][i]) for l in labels) + " |")
