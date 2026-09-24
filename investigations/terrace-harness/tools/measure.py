"""Measure terrace-border quality on generated terrain (terrace-blending.md section 5).

For every window produced by TraceHarness `generate` it reads the top solid block of each column
from the Anvil files, finds borders between two different terraced biomes, cuts +-64-block
transects straight across them every ~16 blocks, and computes:

  ravine      min(side A ref, side B ref) - lowest point within +-12 of the border
              (side refs: median height 16..40 blocks out on each side). >0 = below both sides.
  treads      flat runs (constant height, >= 3 blocks) and the height gaps between successive ones:
              side step heights sA/sB (median gap on each side) and, in the centre band (+-24),
              gaps outside [0.5*min(sA,sB), 1.5*max(sA,sB)] (half or doubled steps).
  slivers     centre-band flat runs 1-2 blocks long bounded by rises >= 2 on both sides.
  ledges      centre-band single 1-block rises between two flat runs >= 3 blocks long.
  orphans     (2D) abrupt riser-line endpoints (skeleton endpoint of a slope>=1 riser whose local
              5x5 relief is still >= 4 blocks) inside the +-12 border band, per 100 blocks of border,
              and the excess over the endpoint density found in the same window's biome interiors.
  rise4       largest height change over any 4 blocks within +-16 of the border (cliff check).

The same transect metrics are computed on control transects cut inside single terraced biomes
(>= 40 blocks from any biome border) so every border number has a no-border baseline.

Usage: python measure.py <gen dir> <windows.json> <out dir> [--label NAME]
"""
import argparse
import json
import math
import os
import sys

import numpy as np
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anvil import load_heightmap  # noqa: E402
from common import family_lut, load_families, load_raster  # noqa: E402

HALF = 64          # transect half length
CENTRE = 12        # ravine search band (blend stencil reaches +-12 blocks on each axis)
SIDE = (16, 40)    # side reference band
SPACING = 16       # border sample spacing
BAND = 24          # centre band for step-spacing / sliver metrics
RAVINE_TOL = 4     # blocks; one sparse-grid cell
ANY_LAND = False   # --any-land: measure every land-land border, not only terraced pairs


def runs(h):
    """Maximal runs of equal value: list of (start, end_exclusive, value)."""
    out = []
    s = 0
    for i in range(1, len(h) + 1):
        if i == len(h) or h[i] != h[s]:
            out.append((s, i, int(h[s])))
            s = i
    return out


def tread_gaps(h, lo, hi, t):
    """Gaps between successive flat treads (runs >= 3) whose centres lie in [lo, hi] of t."""
    rs = [r for r in runs(h) if r[1] - r[0] >= 3 and lo <= t[(r[0] + r[1] - 1) // 2] <= hi]
    return [abs(rs[i + 1][2] - rs[i][2]) for i in range(len(rs) - 1) if rs[i + 1][2] != rs[i][2]], len(rs)


def profile_metrics(h, t):
    """Transect metrics for a height profile h over offsets t (-HALF..HALF)."""
    side_b = h[(t >= -SIDE[1]) & (t <= -SIDE[0])]
    side_a = h[(t >= SIDE[0]) & (t <= SIDE[1])]
    centre = h[np.abs(t) <= CENTRE]
    ref_a, ref_b = float(np.median(side_a)), float(np.median(side_b))
    imin = int(np.argmin(np.where(np.abs(t) <= CENTRE, h, 10 ** 6)))
    ravine = min(ref_a, ref_b) - float(centre.min())

    gaps_b, _ = tread_gaps(h, -HALF, -16, t)
    gaps_a, _ = tread_gaps(h, 16, HALF, t)
    gaps_c, ntread_c = tread_gaps(h, -BAND, BAND, t)
    s_a = float(np.median([g for g in gaps_a if g >= 2])) if any(g >= 2 for g in gaps_a) else None
    s_b = float(np.median([g for g in gaps_b if g >= 2])) if any(g >= 2 for g in gaps_b) else None
    odd = None
    if s_a and s_b:
        lo, hi = 0.5 * min(s_a, s_b), 1.5 * max(s_a, s_b)
        odd = sum(1 for g in gaps_c if g >= 2 and not (lo <= g <= hi))

    rs = runs(h)
    slivers = ledges = 0
    for i in range(1, len(rs) - 1):
        c = (rs[i][0] + rs[i][1] - 1) // 2
        if abs(t[c]) > BAND:
            continue
        up = abs(rs[i][2] - rs[i - 1][2])
        dn = abs(rs[i + 1][2] - rs[i][2])
        if rs[i][1] - rs[i][0] <= 2 and up >= 2 and dn >= 2:
            slivers += 1
    for i in range(len(rs) - 1):
        if abs(t[rs[i][1]]) > BAND:
            continue
        if abs(rs[i + 1][2] - rs[i][2]) == 1 and rs[i][1] - rs[i][0] >= 3 and rs[i + 1][1] - rs[i + 1][0] >= 3:
            ledges += 1
    # Cliff check: largest height change over any 4-block span in the +-16 centre band.
    cb = np.nonzero(np.abs(t) <= 16)[0]
    rise4 = max(abs(int(h[i + 4]) - int(h[i])) for i in cb if i + 4 < len(h))
    return {"refA": ref_a, "refB": ref_b, "centreMin": int(centre.min()), "tMin": int(t[imin]), "rise4": rise4,
            "ravine": ravine, "stepA": s_a, "stepB": s_b, "centreGaps": gaps_c, "centreTreads": ntread_c,
            "oddGaps": odd, "slivers": slivers, "ledges": ledges}


def sample_line(arr, p, n, t):
    xs = np.rint(p[0] + t * n[0]).astype(int)
    zs = np.rint(p[1] + t * n[1]).astype(int)
    return arr[zs, xs], xs, zs


def riser_endpoints(top):
    """Skeleton endpoints of steep 'riser' lines (central-difference slope >= 1)."""
    from skimage.morphology import remove_small_objects, skeletonize
    h = top.astype(float)
    gx = np.zeros_like(h)
    gz = np.zeros_like(h)
    gx[:, 1:-1] = (h[:, 2:] - h[:, :-2]) / 2
    gz[1:-1, :] = (h[2:, :] - h[:-2, :]) / 2
    riser = np.hypot(gx, gz) >= 1.0
    riser = remove_small_objects(riser, max_size=11)
    sk = skeletonize(riser)
    nb = ndimage.convolve(sk.astype(int), np.ones((3, 3), int), mode="constant") - sk
    ends = sk & (nb == 1)
    # An edge "just stops" when the riser is still tall where it ends: local 5x5 relief >= 4 blocks.
    local = ndimage.maximum_filter(top, size=5) - ndimage.minimum_filter(top, size=5)
    abrupt = ends & (local >= 4)
    return riser, sk, abrupt


def measure_window(gen_dir, w, fam, rng):
    x0, z0, size = w["originChunkX"] * 16, w["originChunkZ"] * 16, w["chunks"] * 16
    top, fluid, tname, names = load_heightmap(os.path.join(gen_dir, w["name"], "region"), x0, z0, size, size)
    meta, lab = load_raster(os.path.join(gen_dir, w["name"]), "biomes")
    ids = meta["ids"]
    tmpl, terr = family_lut(ids, fam)
    if ANY_LAND:
        # Every land biome counts (not only terraced ones): checks a change for side effects elsewhere.
        import re
        wet_t = re.compile(r"OCEAN|RIVER|TRENCH|ABYSSAL|SUBMERGED|MARINE|CAVE|SEA")
        wet_i = re.compile(r"RIVER|OCEAN|SEA|BEACH|SHORE|COAST|CAVE")
        terr = np.array([not (wet_t.search(t or "") or wet_i.search(i)) for t, i in zip(tmpl, ids)], dtype=bool)
    T = terr[lab]
    wet = fluid > top

    # Border pixels between two different terraced biomes (different biome IDs).
    border = np.zeros(lab.shape, dtype=bool)
    for dz, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
        q = np.roll(np.roll(lab, dz, 0), dx, 1)
        border |= (lab != q) & T & terr[q]
    anyborder = np.zeros(lab.shape, dtype=bool)
    for dz, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
        anyborder |= lab != np.roll(np.roll(lab, dz, 0), dx, 1)
    anyborder[[0, -1], :] = False
    anyborder[:, [0, -1]] = False
    dist_border = ndimage.distance_transform_edt(~anyborder)
    margin = HALF + 2
    inner = np.zeros(lab.shape, dtype=bool)
    inner[margin:-margin, margin:-margin] = True
    border &= inner

    t = np.arange(-HALF, HALF + 1)
    transects = []
    # Greedy spacing over border pixels in scan order (deterministic).
    zz, xx = np.nonzero(border)
    taken = []
    blur_cache = {}
    for z, x in zip(zz, xx):
        if any((z - a) ** 2 + (x - b) ** 2 < SPACING ** 2 for a, b in taken):
            continue
        a_id = lab[z, x]
        # the other side: the most common different terraced label among 4-neighbours
        nbrs = [lab[z + dz, x + dx] for dz, dx in ((0, 1), (1, 0), (0, -1), (-1, 0))]
        others = [q for q in nbrs if q != a_id and terr[q]]
        if not others:
            continue
        b_id = max(set(others), key=others.count)
        if a_id not in blur_cache:
            blur_cache[a_id] = ndimage.gaussian_filter((lab == a_id).astype(float), 6)
        g = blur_cache[a_id]
        gx = (g[z, x + 1] - g[z, x - 1]) / 2
        gz = (g[z + 1, x] - g[z - 1, x]) / 2
        norm = math.hypot(gx, gz)
        if norm < 1e-4:
            continue
        n = (gx / norm, gz / norm)  # points into A
        hl, xs, zs = sample_line(top, (x, z), n, t)
        ll, _, _ = sample_line(lab, (x, z), n, t)
        wl, _, _ = sample_line(wet, (x, z), n, t)
        side_a = ll[(t >= 6) & (t <= 24)]
        side_b = ll[(t >= -24) & (t <= -6)]
        if (side_a == a_id).mean() < 0.7 or (side_b == b_id).mean() < 0.7:
            continue  # not a clean two-biome crossing
        taken.append((z, x))
        m = profile_metrics(hl, t)
        centre_labels = set(ids[i] for i in ll[np.abs(t) <= CENTRE])
        m.update({"x": int(x0 + x), "z": int(z0 + z), "nx": n[0], "nz": n[1],
                  "A": ids[a_id], "B": ids[b_id], "tmplA": tmpl[a_id], "tmplB": tmpl[b_id],
                  "crossTemplate": tmpl[a_id] != tmpl[b_id],
                  "wetCentre": bool(wl[np.abs(t) <= CENTRE].any()),
                  "centreLabels": sorted(centre_labels),
                  "profile": hl.astype(int).tolist()})
        transects.append(m)

    # Control transects: inside one terraced biome, >= 40 blocks from any biome border.
    controls = []
    cand = np.argwhere(T & inner & (dist_border >= 40))
    if len(cand):
        pick = cand[rng.choice(len(cand), size=min(40, len(cand)), replace=False)]
        for z, x in pick:
            ang = rng.uniform(0, math.pi)
            n = (math.cos(ang), math.sin(ang))
            hl, _, _ = sample_line(top, (x, z), n, t)
            m = profile_metrics(hl, t)
            m.update({"x": int(x0 + x), "z": int(z0 + z), "A": ids[lab[z, x]], "tmplA": tmpl[lab[z, x]],
                      "profile": hl.astype(int).tolist()})
            controls.append(m)

    # 2D orphaned step edges.
    riser, sk, ends = riser_endpoints(top)
    tborder = np.zeros(lab.shape, dtype=bool)
    for dz, dx in ((0, 1), (1, 0)):
        q = np.roll(np.roll(lab, -dz, 0), -dx, 1)
        tborder |= (lab != q) & T & terr[q]
    tborder &= inner
    band = (ndimage.distance_transform_edt(~tborder) <= 12) & inner & ~wet
    interior = T & (dist_border >= 24) & inner & ~wet
    blen = max(1, int(tborder.sum()))  # border length ~ number of border pixels (4-connected)
    e_band = int((ends & band).sum())
    dens_int = float((ends & interior).sum()) / max(1, int(interior.sum()))
    expected = dens_int * int(band.sum())
    orphans = {"borderLength": blen, "bandArea": int(band.sum()), "bandEndpoints": e_band,
               "interiorArea": int(interior.sum()), "interiorEndpointDensity": dens_int,
               "expectedBandEndpoints": expected,
               "excessPer100": (e_band - expected) / blen * 100,
               "bandEndpointsPer100": e_band / blen * 100}

    return {"window": w["name"], "pair": w.get("pair"), "x0": x0, "z0": z0, "size": size,
            "transects": transects, "controls": controls, "orphans": orphans}, (top, fluid, lab, ids, tmpl, riser, ends)


def render(path, res, data, worst=6):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import LightSource

    top, fluid, lab, ids, tmpl, riser, ends = data
    ls = LightSource(azdeg=315, altdeg=45)
    fig = plt.figure(figsize=(16, 8.5))
    ax = fig.add_axes([0.02, 0.05, 0.5, 0.9])
    rgb = ls.shade(top.astype(float), cmap=plt.cm.terrain, vert_exag=1.5, blend_mode="soft",
                   vmin=float(top.min()) - 20, vmax=float(top.max()) + 10)
    ext = (res["x0"], res["x0"] + res["size"], res["z0"] + res["size"], res["z0"])
    ax.imshow(rgb, extent=ext, interpolation="nearest")
    edge = np.zeros(lab.shape, bool)
    edge[:, 1:] |= lab[:, 1:] != lab[:, :-1]
    edge[1:, :] |= lab[1:, :] != lab[:-1, :]
    ov = np.zeros(lab.shape + (4,))
    ov[edge] = (0, 0, 0, 0.9)
    wet = fluid > top
    ov[wet & ~edge] = (0.1, 0.3, 1.0, 0.35)
    ax.imshow(ov, extent=ext, interpolation="nearest")
    for tr in res["transects"]:
        t = np.array([-HALF, HALF])
        c = "red" if tr["ravine"] >= RAVINE_TOL else "white"
        ax.plot(tr["x"] + t * tr["nx"], tr["z"] + t * tr["nz"], color=c, lw=0.8)
    # label biomes at their centroids
    for i in np.unique(lab):
        zz, xx = np.nonzero(lab == i)
        ax.text(res["x0"] + xx.mean(), res["z0"] + zz.mean(), f"{ids[i]}\n{tmpl[i]}", fontsize=7,
                ha="center", color="black", bbox=dict(fc="white", alpha=0.6, lw=0))
    ax.set_title(f"{res['window']}  {res.get('pair')}  (red: ravine >= {RAVINE_TOL})", fontsize=9)
    worst_tr = sorted(res["transects"], key=lambda r: -r["ravine"])[:worst]
    for k, tr in enumerate(worst_tr):
        axp = fig.add_axes([0.57 + (k % 2) * 0.215, 0.70 - (k // 2) * 0.31, 0.19, 0.24])
        tt = np.arange(-HALF, HALF + 1)
        axp.step(tt, tr["profile"], where="mid", color="k", lw=0.9)
        axp.axvspan(-CENTRE, CENTRE, color="orange", alpha=0.15)
        axp.axhline(tr["refA"], color="g", lw=0.6, ls="--")
        axp.axhline(tr["refB"], color="b", lw=0.6, ls="--")
        axp.set_title(f"({tr['x']},{tr['z']}) ravine={tr['ravine']:.0f}\nB={tr['B']} | A={tr['A']}", fontsize=7)
        axp.tick_params(labelsize=6)
    fig.savefig(path, dpi=110)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("gen")
    ap.add_argument("windows")
    ap.add_argument("out")
    ap.add_argument("--label", default=None)
    ap.add_argument("--no-images", action="store_true")
    ap.add_argument("--any-land", action="store_true")
    a = ap.parse_args()
    global ANY_LAND
    ANY_LAND = a.any_land
    fam = load_families()
    wj = json.load(open(a.windows))
    os.makedirs(a.out, exist_ok=True)
    rng = np.random.default_rng(12345)
    results = []
    for w in wj["windows"]:
        if not os.path.exists(os.path.join(a.gen, w["name"], "biomes.json")):
            print("missing", w["name"])
            continue
        res, data = measure_window(a.gen, w, fam, rng)
        res["seed"] = wj["seed"]
        results.append(res)
        if not a.no_images:
            render(os.path.join(a.out, f"{w['name']}.png"), res, data)
        rv = [r["ravine"] for r in res["transects"]]
        cv = [r["ravine"] for r in res["controls"]]
        print(f"{w['name']}: {len(rv)} transects, ravine>= {RAVINE_TOL}: {sum(v >= RAVINE_TOL for v in rv)}, "
              f"max {max(rv, default=0):.0f}; controls {len(cv)} (>= tol: {sum(v >= RAVINE_TOL for v in cv)}); "
              f"orphan excess/100 {res['orphans']['excessPer100']:.1f}")
    json.dump({"label": a.label, "seed": wj["seed"], "results": results}, open(os.path.join(a.out, "measure.json"), "w"))


if __name__ == "__main__":
    main()
