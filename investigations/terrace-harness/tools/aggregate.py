"""Aggregate measure.json files into a summary (JSON + Markdown), optionally comparing two labels.

Usage:
  python aggregate.py <label>                       -> reports/<label>/summary.{json,md}
  python aggregate.py <label> --compare <baseline>  -> also a before/after table (same windows)
"""
import argparse
import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import LAB  # noqa: E402

TOL = 4


def stats(trs, key="ravine"):
    v = np.array([t[key] for t in trs], dtype=float)
    if len(v) == 0:
        return {"n": 0}
    r4 = np.array([t.get("rise4", np.nan) for t in trs], dtype=float)
    return {"n": int(len(v)), "median": float(np.median(v)), "p90": float(np.percentile(v, 90)),
            "rise4p90": float(np.nanpercentile(r4, 90)) if np.isfinite(r4).any() else None,
            "rise4max": float(np.nanmax(r4)) if np.isfinite(r4).any() else None,
            "max": float(v.max()), "fracGE4": float((v >= 4).mean()), "fracGE8": float((v >= 8).mean())}


def extra(trs):
    if not trs:
        return {}
    odd = [t["oddGaps"] for t in trs if t.get("oddGaps") is not None]
    return {"slivers": float(np.mean([t["slivers"] for t in trs])),
            "ledges": float(np.mean([t["ledges"] for t in trs])),
            "oddGapsPerTransect": float(np.mean(odd)) if odd else None,
            "nWithStepsBothSides": len(odd)}


def load(label, areas=None):
    out = []
    for f in sorted(glob.glob(os.path.join(LAB, "reports", label, "*", "measure.json"))):
        area = os.path.basename(os.path.dirname(f))
        if areas and area not in areas:
            continue
        j = json.load(open(f))
        for r in j["results"]:
            r["area"] = area
            out.append(r)
    return out


def summarise(results):
    trs = [t for r in results for t in r["transects"]]
    ctl = [t for r in results for t in r["controls"]]
    dry = [t for t in trs if not t["wetCentre"]]
    s = {"windows": len(results), "transects": stats(trs), "dryTransects": stats(dry), "controls": stats(ctl),
         "transectExtra": extra(trs), "controlExtra": extra(ctl),
         "orphanExcessPer100": float(np.mean([r["orphans"]["excessPer100"] for r in results])) if results else None,
         "orphanBandPer100": float(np.mean([r["orphans"]["bandEndpointsPer100"] for r in results])) if results else None}
    by_area = {}
    for r in results:
        by_area.setdefault((r["area"], r["seed"]), []).append(r)
    s["byArea"] = []
    for (area, seed), rs in sorted(by_area.items()):
        t = [x for r in rs for x in r["transects"]]
        c = [x for r in rs for x in r["controls"]]
        s["byArea"].append({"area": area, "seed": seed, "windows": len(rs), "transects": stats(t),
                            "controls": stats(c),
                            "orphanExcessPer100": float(np.mean([r["orphans"]["excessPer100"] for r in rs]))})
    by_pair = {}
    for t in trs:
        key = " | ".join(sorted((t["tmplA"], t["tmplB"])))
        by_pair.setdefault(key, []).append(t)
    s["byTemplatePair"] = {k: {**stats(v), **extra(v)} for k, v in sorted(by_pair.items(), key=lambda kv: -len(kv[1]))}
    by_fam = {}
    for t in trs:
        for f in {t["tmplA"], t["tmplB"]}:
            by_fam.setdefault(f, []).append(t)
    s["byFamily"] = {k: stats(v) for k, v in sorted(by_fam.items())}
    by_ctl = {}
    for t in ctl:
        by_ctl.setdefault(t["tmplA"], []).append(t)
    s["controlsByFamily"] = {k: {**stats(v), **extra(v)} for k, v in sorted(by_ctl.items())}
    worst = sorted(trs, key=lambda t: -t["ravine"])[:25]
    s["worst"] = [{k: t[k] for k in ("x", "z", "A", "B", "tmplA", "tmplB", "ravine", "refA", "refB", "centreMin",
                                     "tMin", "wetCentre", "centreLabels")} for t in worst]
    return s


def fmt(v, p=1):
    return "-" if v is None else (f"{v:.{p}f}" if isinstance(v, float) else str(v))


def markdown(label, s, cmp=None):
    L = [f"# Terrace border metrics: `{label}`", ""]
    L.append(f"Windows: {s['windows']}. Transects cross borders between two different terraced biomes; "
             f"controls are cut inside single terraced biomes (>= 40 blocks from any border). "
             f"Ravine = min(side refs) - lowest point within +-12 of the border; tolerance {TOL} blocks.")
    L.append("")
    L.append("| set | n | ravine median | p90 | max | >=4 | >=8 | slivers/tr | 1-block ledges/tr | odd step gaps/tr | rise4 p90 | rise4 max |")
    L.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for name, st, ex in (("border transects", s["transects"], s["transectExtra"]),
                         ("  of which dry centre", s["dryTransects"], {}),
                         ("controls", s["controls"], s["controlExtra"])):
        L.append(f"| {name} | {st['n']} | {fmt(st.get('median'))} | {fmt(st.get('p90'))} | {fmt(st.get('max'))} | "
                 f"{fmt(100 * st['fracGE4'], 0) + '%' if st['n'] else '-'} | {fmt(100 * st['fracGE8'], 0) + '%' if st['n'] else '-'} | "
                 f"{fmt(ex.get('slivers'), 2)} | {fmt(ex.get('ledges'), 2)} | {fmt(ex.get('oddGapsPerTransect'), 2)} | "
                 f"{fmt(st.get('rise4p90'))} | {fmt(st.get('rise4max'))} |")
    L.append("")
    L.append(f"Orphaned step edges (abrupt riser endpoints within 12 blocks of a terraced border, per 100 border blocks): "
             f"{fmt(s['orphanBandPer100'])}; excess over the window's interior endpoint density: {fmt(s['orphanExcessPer100'])}.")
    L.append("")
    L.append("## By seed / area")
    L.append("")
    L.append("| area | seed | windows | transects | ravine median | p90 | max | >=4 | controls >=4 | orphan excess/100 |")
    L.append("|---|---|---|---|---|---|---|---|---|---|")
    for a in s["byArea"]:
        t, c = a["transects"], a["controls"]
        L.append(f"| {a['area']} | {a['seed']} | {a['windows']} | {t['n']} | {fmt(t.get('median'))} | {fmt(t.get('p90'))} | "
                 f"{fmt(t.get('max'))} | {fmt(100 * t['fracGE4'], 0) if t['n'] else '-'}% | "
                 f"{fmt(100 * c['fracGE4'], 0) if c['n'] else '-'}% | {fmt(a['orphanExcessPer100'])} |")
    L.append("")
    L.append("## By template pair")
    L.append("")
    L.append("| templates | n | median | p90 | max | >=4 | slivers/tr | ledges/tr | odd gaps/tr |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    for k, v in s["byTemplatePair"].items():
        L.append(f"| {k} | {v['n']} | {fmt(v.get('median'))} | {fmt(v.get('p90'))} | {fmt(v.get('max'))} | "
                 f"{fmt(100 * v['fracGE4'], 0)}% | {fmt(v.get('slivers'), 2)} | {fmt(v.get('ledges'), 2)} | "
                 f"{fmt(v.get('oddGapsPerTransect'), 2)} |")
    L.append("")
    L.append("## Controls by family")
    L.append("")
    L.append("| template | n | median | p90 | max | >=4 | slivers/tr | ledges/tr |")
    L.append("|---|---|---|---|---|---|---|---|")
    for k, v in s["controlsByFamily"].items():
        L.append(f"| {k} | {v['n']} | {fmt(v.get('median'))} | {fmt(v.get('p90'))} | {fmt(v.get('max'))} | "
                 f"{fmt(100 * v['fracGE4'], 0)}% | {fmt(v.get('slivers'), 2)} | {fmt(v.get('ledges'), 2)} |")
    L.append("")
    L.append("## Worst transects")
    L.append("")
    L.append("| x | z | B | A | ravine | refB | refA | min | wet |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    for w in s["worst"]:
        L.append(f"| {w['x']} | {w['z']} | {w['B']} | {w['A']} | {w['ravine']:.0f} | {w['refB']:.0f} | {w['refA']:.0f} | "
                 f"{w['centreMin']} | {'yes' if w['wetCentre'] else ''} |")
    if cmp:
        L += ["", f"## Before / after (`{cmp['baseline']}` -> `{label}`, same windows)", ""]
        L.append("| metric | before | after |")
        L.append("|---|---|---|")
        for row in cmp["rows"]:
            L.append(f"| {row[0]} | {row[1]} | {row[2]} |")
    return "\n".join(L) + "\n"


def compare(base, new):
    bw = {(r["area"], r["window"]) for r in base}
    nw = {(r["area"], r["window"]) for r in new}
    common = bw & nw
    b = [r for r in base if (r["area"], r["window"]) in common]
    n = [r for r in new if (r["area"], r["window"]) in common]
    sb, sn = summarise(b), summarise(n)
    rows = []
    for name, key in (("border transects n", "n"), ("ravine median", "median"), ("ravine p90", "p90"),
                      ("ravine max", "max"), ("share >= 4 blocks", "fracGE4"), ("share >= 8 blocks", "fracGE8")):
        f = (lambda v: f"{100 * v:.0f}%") if key.startswith("frac") else (lambda v: fmt(v))
        rows.append((name, f(sb["transects"].get(key, 0)), f(sn["transects"].get(key, 0))))
    rows.append(("controls share >= 4", f"{100 * sb['controls']['fracGE4']:.0f}%", f"{100 * sn['controls']['fracGE4']:.0f}%"))
    rows.append(("cliff: rise over 4 blocks, p90", fmt(sb["transects"].get("rise4p90")), fmt(sn["transects"].get("rise4p90"))))
    rows.append(("cliff: rise over 4 blocks, max", fmt(sb["transects"].get("rise4max")), fmt(sn["transects"].get("rise4max"))))
    for k in ("slivers", "ledges", "oddGapsPerTransect"):
        rows.append((f"{k} per transect", fmt(sb["transectExtra"].get(k), 2), fmt(sn["transectExtra"].get(k), 2)))
    rows.append(("orphan endpoints / 100 border blocks", fmt(sb["orphanBandPer100"]), fmt(sn["orphanBandPer100"])))
    rows.append(("orphan excess / 100", fmt(sb["orphanExcessPer100"]), fmt(sn["orphanExcessPer100"])))
    return {"rows": rows, "windows": len(common)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--compare")
    ap.add_argument("--areas", default=None, help="comma list of areas (default: all)")
    ap.add_argument("--suffix", default="", help="output file suffix, e.g. _main")
    a = ap.parse_args()
    areas = set(a.areas.split(",")) if a.areas else None
    res = load(a.label, areas)
    s = summarise(res)
    cmp = None
    if a.compare:
        cmp = compare(load(a.compare, areas), res)
        cmp["baseline"] = a.compare
        s["compare"] = cmp
    out = os.path.join(LAB, "reports", a.label)
    json.dump(s, open(os.path.join(out, f"summary{a.suffix}.json"), "w"), indent=1)
    open(os.path.join(out, f"summary{a.suffix}.md"), "w").write(markdown(a.label, s, cmp))
    print(open(os.path.join(out, f"summary{a.suffix}.md")).read())


if __name__ == "__main__":
    main()
