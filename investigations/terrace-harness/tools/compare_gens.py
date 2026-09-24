"""Compare two generation outputs window by window: heights (top solid + fluid top) and biome rasters.

Usage: python compare_gens.py <dirA> <dirB> [areas...]   (dirs contain <area>/<window>/...)
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anvil import load_heightmap  # noqa: E402
from common import LAB, load_raster  # noqa: E402

a, b = sys.argv[1], sys.argv[2]
areas = sys.argv[3:] or ["s1a", "s1b", "s2a", "s3a"]
tot_cols = diff_h = diff_b = 0
bad = []
for area in areas:
    for w in json.load(open(os.path.join(LAB, "data", area, "windows.json")))["windows"]:
        x0, z0, n = w["originChunkX"] * 16, w["originChunkZ"] * 16, w["chunks"] * 16
        pa, pb = os.path.join(a, area, w["name"]), os.path.join(b, area, w["name"])
        ta, fa = load_heightmap(os.path.join(pa, "region"), x0, z0, n, n)[:2]
        tb, fb = load_heightmap(os.path.join(pb, "region"), x0, z0, n, n)[:2]
        ma, la = load_raster(pa, "biomes")
        mb, lb = load_raster(pb, "biomes")
        ba, bb = np.array(ma["ids"])[la], np.array(mb["ids"])[lb]
        dh = int(((ta != tb) | (fa != fb)).sum())
        db = int((ba != bb).sum())
        tot_cols += ta.size
        diff_h += dh
        diff_b += db
        if dh or db:
            bad.append(f"{area}/{w['name']}: heights {dh}, biomes {db}")
print(f"{len(bad)} of windows differ; columns {tot_cols}, height diffs {diff_h}, biome diffs {diff_b}")
for line in bad[:20]:
    print("  " + line)
