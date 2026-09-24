"""Diagnose the worst border ravines: probe each biome's own terrain along the transect and classify.

For the N worst dry transects of a measured label it probes (TerraceHarness probe) every column
along the transect for: each blend contributor's own surface height, both border biomes' own
heights (whether or not they are in the blend stencil), the exact un-interpolated blended height,
and the pack samplers behind biomeInfluence. Then, at the ravine bottom t*:

  cause 1  both biomes' own heights are already low at t* (<= min(side refs) - tol):
           something lowers both biomes there.  Sub-type:
             1-fade   biomeInfluence(t*) < 0.95 (relief faded); the limiting normDistIntoBiome
                      term (river / cell / mesa / coast / spot) is reported
             1-shape  biomeInfluence ~ 1: the terrain functions themselves are low there
  cause 2  the exact blend is below every contributor's own height by >= tol (non-height-map density)
  carver   the generated surface is >= tol below the exact blend and the carving sampler is
           positive at the blended top (a cave/canyon opening, unrelated to blending)
  interp   the generated surface is >= tol below the exact blend (4-block sparse interpolation)
  mixed    none of the above alone explains the dip (reported with the numbers)

Usage: python diagnose.py <label> [--worst 30] [--run run]
"""
import argparse
import json
import os
import subprocess
import sys
from collections import Counter

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import LAB  # noqa: E402
from aggregate import load  # noqa: E402

TOL = 4
SAMPLERS = ["biomeInfluence", "normDistIntoBiome", "elevationDetailedBlockHeight", "continentalRiverDist",
            "BiomeShapeLandmassDist2Center", "mesaFootDist", "dist2Coast", "spotDistance", "spotRadius",
            "spotCenterNotNearRiver"]
BASE_Y = 65  # customization.yml terrain-base-y-level
JAVA = r"C:\JAVA\jdk-25.0.1\bin\java.exe"


def lerp(t, at, a, bt, b):
    if at < bt:
        if t <= at:
            return a
        if t >= bt:
            return b
    else:
        if t >= at:
            return a
        if t <= bt:
            return b
    return a * (t - bt) / (at - bt) + b * (t - at) / (bt - at)


def norm_terms(s):
    """normDistIntoBiome's five terms (elevation.yml), from the probed samplers."""
    return {
        "river": lerp(s["continentalRiverDist"], 0.15, 0, 1.0, 1),
        "cell": lerp(s["BiomeShapeLandmassDist2Center"], -0.9, 1, 0, 0),
        "mesa": lerp(abs(s["mesaFootDist"]), 50 / 1000, 1, 0, 0),
        "coast": lerp(abs(s["dist2Coast"]), 0, 0, 50 / 240, 1),
        "spot": lerp(s["spotDistance"] - (s["spotRadius"] + 50), 0, 0, 50, 1) if s["spotCenterNotNearRiver"] else 1,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--worst", type=int, default=30)
    ap.add_argument("--per-window", type=int, default=3)
    ap.add_argument("--run", default=os.path.join(LAB, "run"))
    ap.add_argument("--reuse", action="store_true", help="reuse existing probe output")
    a = ap.parse_args()

    res = load(a.label)
    cands = []
    for r in res:
        for i, t in enumerate(r["transects"]):
            if not t["wetCentre"] and t["ravine"] >= TOL:
                cands.append((t["ravine"], r["area"], r["window"], r["seed"], i, t))
    cands.sort(key=lambda c: -c[0])
    chosen, per = [], Counter()
    for c in cands:
        if per[(c[1], c[2])] >= a.per_window:
            continue
        per[(c[1], c[2])] += 1
        chosen.append(c)
        if len(chosen) >= a.worst:
            break

    out = os.path.join(LAB, "reports", a.label, "diagnosis")
    os.makedirs(out, exist_ok=True)
    ts = [t for t in range(-64, 65) if abs(t) <= 20 or t % 4 == 0]
    by_seed = {}
    for k, (_, area, win, seed, i, t) in enumerate(chosen):
        for tt in ts:
            x = int(round(t["x"] + tt * t["nx"]))
            z = int(round(t["z"] + tt * t["nz"]))
            by_seed.setdefault(seed, []).append(f"{x} {z} {k}:{tt} {t['A']},{t['B']}")
    probes = {}
    for seed, lines in by_seed.items():
        pts = os.path.join(out, f"points_{seed}.txt")
        pj = os.path.join(out, f"probe_{seed}.jsonl")
        open(pts, "w").write("\n".join(lines) + "\n")
        if not (a.reuse and os.path.exists(pj)):
            cmd = [JAVA, "-Xmx6g", "-cp", "terra-cli.jar", "com.dfsek.terra.cli.harness.TerraceHarness", "probe",
                   "--seed", str(seed), "--points", pts, "--out", pj, "--samplers", ",".join(SAMPLERS)]
            subprocess.run(cmd, cwd=a.run, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for line in open(pj):
            j = json.loads(line)
            k, tt = j["tag"].split(":")
            probes.setdefault(int(k), {})[int(tt)] = j

    cases = []
    for k, (_, area, win, seed, i, t) in enumerate(chosen):
        P = probes[k]
        prof = np.array(t["profile"])
        tstar = t["tMin"]
        near = min(P, key=lambda tt: abs(tt - tstar))
        p = P[near]
        gen = int(prof[near + 64])
        own_a, own_b = p["own"].get(t["A"]), p["own"].get(t["B"])
        contrib = [(c["biomes"], c["weight"], c["ownTop"], c["heightMap"]) for c in p["contributors"]]
        min_contrib = min(c[2] for c in contrib)
        s = p["samplers"]
        terms = norm_terms(s)
        limiting = min(terms, key=terms.get)
        floor = BASE_Y + s["elevationDetailedBlockHeight"]
        side_ref = min(t["refA"], t["refB"])
        own_a_side = np.median([P[tt]["own"][t["A"]] for tt in P if 16 <= tt <= 40])
        own_b_side = np.median([P[tt]["own"][t["B"]] for tt in P if -40 <= tt <= -16])
        both_low = max(own_a, own_b) <= side_ref - TOL
        cause2 = p["blendedTop"] <= min_contrib - TOL
        interp = gen <= p["blendedTop"] - TOL
        if both_low:
            cause = "1-fade" if s["biomeInfluence"] < 0.95 else "1-shape"
        elif cause2:
            cause = "2"
        elif interp and p["carvingAtTop"] is not None and p["carvingAtTop"] > 0:
            cause = "carver"
        elif interp:
            cause = "interp"
        else:
            cause = "mixed"
        cases.append({
            "area": area, "window": win, "seed": seed, "x": t["x"], "z": t["z"], "A": t["A"], "B": t["B"],
            "tmplA": t["tmplA"], "tmplB": t["tmplB"], "ravine": t["ravine"], "tStar": near,
            "generated": gen, "blendedExact": p["blendedTop"], "carvingAtTop": p["carvingAtTop"], "ownA": own_a, "ownB": own_b,
            "ownAside": float(own_a_side), "ownBside": float(own_b_side), "refA": t["refA"], "refB": t["refB"],
            "contributors": contrib, "allHeightMap": all(c[3] for c in contrib),
            "biomeInfluence": s["biomeInfluence"], "normDist": s["normDistIntoBiome"],
            "normTerms": terms, "limitingTerm": limiting, "fadeFloor": floor, "cause": cause,
            "profileOwnA": [[tt, P[tt]["own"][t["A"]]] for tt in sorted(P)],
            "profileOwnB": [[tt, P[tt]["own"][t["B"]]] for tt in sorted(P)],
            "profileBlended": [[tt, P[tt]["blendedTop"]] for tt in sorted(P)],
            "profileBI": [[tt, P[tt]["samplers"]["biomeInfluence"]] for tt in sorted(P)],
            "profileFloor": [[tt, BASE_Y + P[tt]["samplers"]["elevationDetailedBlockHeight"]] for tt in sorted(P)],
            "profileLimiting": [[tt, min(norm_terms(P[tt]["samplers"]).items(), key=lambda kv: kv[1])[0]]
                                for tt in sorted(P)],
            "profileGenerated": t["profile"],
        })

    json.dump(cases, open(os.path.join(out, "diagnosis.json"), "w"), indent=1)
    render(cases, out)
    counts = Counter(c["cause"] for c in cases)
    lim = Counter(c["limitingTerm"] for c in cases if c["cause"] == "1-fade")
    L = [f"# Ravine diagnosis: `{a.label}`", "",
         f"{len(cases)} worst dry border transects (ravine >= {TOL}, max {a.per_window} per window).", "",
         "Cause counts: " + ", ".join(f"{k}: {v}" for k, v in counts.most_common()), "",
         "Limiting normDistIntoBiome term at the ravine bottom (cause 1-fade): "
         + ", ".join(f"{k}: {v}" for k, v in lim.most_common()), "",
         "All contributors pure height maps at t*: "
         + f"{sum(c['allHeightMap'] for c in cases)}/{len(cases)}", "",
         "| # | x | z | B -> A | ravine | gen | exact blend | own B | own A | own B side | own A side | biomeInfluence | limiting term | cause |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for n, c in enumerate(cases):
        L.append(f"| {n} | {c['x']} | {c['z']} | {c['B']} -> {c['A']} | {c['ravine']:.0f} | {c['generated']} | "
                 f"{c['blendedExact']} | {c['ownB']} | {c['ownA']} | {c['ownBside']:.0f} | {c['ownAside']:.0f} | "
                 f"{c['biomeInfluence']:.2f} | {c['limitingTerm']} ({c['normTerms'][c['limitingTerm']]:.2f}) | {c['cause']} |")
    open(os.path.join(out, "diagnosis.md"), "w").write("\n".join(L) + "\n")
    print("\n".join(L[:8]))


def render(cases, out, n=12):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    k = min(n, len(cases))
    if k == 0:
        return
    cols = 3
    rows = (k + cols - 1) // cols
    fig, axs = plt.subplots(rows, cols, figsize=(15, 3.6 * rows), squeeze=False)
    for i in range(rows * cols):
        ax = axs[i // cols][i % cols]
        if i >= k:
            ax.axis("off")
            continue
        c = cases[i]
        tt = np.arange(-64, 65)
        ax.step(tt, c["profileGenerated"], where="mid", color="k", lw=1.2, label="generated")
        for key, col, lab in (("profileOwnA", "g", "own A"), ("profileOwnB", "b", "own B"),
                              ("profileBlended", "m", "exact blend"), ("profileFloor", "0.5", "fade floor (base+E)")):
            arr = np.array(c[key], dtype=float)
            ax.plot(arr[:, 0], arr[:, 1], color=col, lw=0.9, ls="--" if key == "profileFloor" else "-", label=lab)
        ax2 = ax.twinx()
        bi = np.array(c["profileBI"], dtype=float)
        ax2.plot(bi[:, 0], bi[:, 1], color="orange", lw=0.8, label="biomeInfluence")
        ax2.set_ylim(-0.05, 3)
        ax2.tick_params(labelsize=6)
        ax.axvline(c["tStar"], color="r", lw=0.5)
        ax.set_title(f"#{i} ({c['x']},{c['z']}) {c['B']} -> {c['A']}\nravine {c['ravine']:.0f}, cause {c['cause']}, "
                     f"limit {c['limitingTerm']}", fontsize=7)
        ax.tick_params(labelsize=6)
        if i == 0:
            ax.legend(fontsize=6, loc="upper left")
            ax2.legend(fontsize=6, loc="upper right")
    fig.tight_layout()
    fig.savefig(os.path.join(out, "diagnosis.png"), dpi=100)
    plt.close(fig)


if __name__ == "__main__":
    main()
