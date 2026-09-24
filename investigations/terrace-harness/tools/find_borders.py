"""Pick terrain windows around borders between two terraced biomes, from a coarse biome survey.

Each window is W x W chunks centred on the densest stretch of one biome pair's border, so that
+-64-block transects fit inside. Pairs whose biomes use different terrain templates (different
step systems) are chosen first; each pair gets at most `--per-pair` windows.

Usage: python find_borders.py <survey dir> <out prefix> [--windows N] [--chunks W] [--same-template]
Writes <out prefix>.txt (for TerraceHarness generate) and <out prefix>.json (pair metadata).
"""
import argparse
import json
import os
import sys

import numpy as np
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import family_lut, load_families, load_raster  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("survey")
    ap.add_argument("out")
    ap.add_argument("--name", default="survey")
    ap.add_argument("--windows", type=int, default=24)
    ap.add_argument("--chunks", type=int, default=16)
    ap.add_argument("--per-pair", type=int, default=2)
    ap.add_argument("--same-template", action="store_true", help="also allow pairs sharing one template")
    ap.add_argument("--prefix", default="w")
    ap.add_argument("--templates", default=None, help="only pairs of exactly these two templates, e.g. A,B")
    a = ap.parse_args()

    meta, d = load_raster(a.survey, a.name)
    fam = load_families()
    ids = meta["ids"]
    tmpl, terr = family_lut(ids, fam)
    step, x0, z0 = meta["step"], meta["x0"], meta["z0"]
    win_blocks = a.chunks * 16
    core = max(1, 128 // step)  # border cells counted within the window's central 128 blocks

    # Border cells between two different terraced biomes, per pair.
    pairs = {}
    for dz, dx in ((0, 1), (1, 0)):
        p = d[: d.shape[0] - dz, : d.shape[1] - dx]
        q = d[dz:, dx:]
        m = (p != q) & terr[p] & terr[q]
        zz, xx = np.nonzero(m)
        for z, x, i, j in zip(zz, xx, p[m], q[m]):
            if not a.same_template and tmpl[i] == tmpl[j]:
                continue
            if a.templates and sorted((tmpl[i], tmpl[j])) != sorted(a.templates.split(",")):
                continue
            key = tuple(sorted((ids[i], ids[j])))
            pairs.setdefault(key, []).append((z, x))

    cross = lambda k: tmpl[ids.index(k[0])] != tmpl[ids.index(k[1])]
    fams_of = lambda k: {tmpl[ids.index(k[0])], tmpl[ids.index(k[1])]}
    # Greedy order: first pairs that bring in a terrace family not yet covered (so rare families such
    # as sinkholes or travertine get a window), then template pairs not yet covered, then by length.
    order, seen_f, seen_tp, rest = [], set(), set(), set(pairs)
    while rest:
        best = max(rest, key=lambda k: (len(fams_of(k) - seen_f), tuple(sorted(fams_of(k))) not in seen_tp,
                                        cross(k), len(pairs[k])))
        order.append(best)
        rest.discard(best)
        seen_f |= fams_of(best)
        seen_tp.add(tuple(sorted(fams_of(best))))

    chosen = []
    taken = np.zeros(d.shape, dtype=bool)
    half = win_blocks // 2 // step
    for key in order:
        if len(chosen) >= a.windows:
            break
        mask = np.zeros(d.shape, dtype=np.float32)
        for z, x in pairs[key]:
            mask[z, x] = 1
        dens = ndimage.uniform_filter(mask, size=core, mode="constant") * core * core
        for _ in range(a.per_pair):
            dens_ok = np.where(taken, 0, dens)
            # keep windows fully inside the survey
            dens_ok[:half, :] = 0
            dens_ok[-half:, :] = 0
            dens_ok[:, :half] = 0
            dens_ok[:, -half:] = 0
            iz, ix = np.unravel_index(np.argmax(dens_ok), dens_ok.shape)
            if dens_ok[iz, ix] < 3:  # need a real stretch of border, not a single cell
                break
            cx = x0 + ix * step
            cz = z0 + iz * step
            ox = (cx - win_blocks // 2) // 16
            oz = (cz - win_blocks // 2) // 16
            chosen.append({"name": f"{a.prefix}{len(chosen):02d}", "originChunkX": int(ox), "originChunkZ": int(oz),
                           "chunks": a.chunks, "pair": list(key),
                           "templates": [tmpl[ids.index(key[0])], tmpl[ids.index(key[1])]],
                           "crossTemplate": bool(cross(key)), "borderCellsInCore": float(dens_ok[iz, ix]),
                           "centre": [int(cx), int(cz)]})
            taken[max(0, iz - 2 * half):iz + 2 * half, max(0, ix - 2 * half):ix + 2 * half] = True
            if len(chosen) >= a.windows:
                break

    with open(a.out + ".txt", "w") as f:
        for w in chosen:
            f.write(f"{w['name']} {w['originChunkX']} {w['originChunkZ']} {w['chunks']} {w['chunks']}\n")
    json.dump({"seed": meta["seed"], "survey": os.path.abspath(a.survey), "windows": chosen},
              open(a.out + ".json", "w"), indent=1)
    print(f"{len(pairs)} terraced pairs ({sum(cross(k) for k in pairs)} cross-template); chose {len(chosen)} windows")
    for w in chosen:
        print(f"  {w['name']} {w['pair']} {w['templates']} centre={w['centre']} cells={w['borderCellsInCore']:.0f}")


if __name__ == "__main__":
    main()
