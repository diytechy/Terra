"""Compare labels (variants) on the transects of one template pair, same windows in every label.

Usage: python pair_compare.py <tmplA> <tmplB> <label> [<label> ...] [--areas s1a,mt_s1a,...]
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import extra, load, stats  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("labels", nargs="+")
    ap.add_argument("--areas", default=None)
    a = ap.parse_args()
    pair = sorted((a.a, a.b))
    data = {lab: load(lab) for lab in a.labels}
    areas = set(a.areas.split(",")) if a.areas else None
    common = None
    for lab, res in data.items():
        keys = {(r["area"], r["window"]) for r in res if areas is None or r["area"] in areas}
        common = keys if common is None else common & keys
    rows = []
    for lab, res in data.items():
        rs = [r for r in res if (r["area"], r["window"]) in common]
        trs = [t for r in rs for t in r["transects"] if sorted((t["tmplA"], t["tmplB"])) == pair]
        s, e = stats(trs), extra(trs)
        rows.append((lab, s, e))
    print(f"Template pair {pair[0]} | {pair[1]} over {len(common)} common windows")
    print("| variant | n | ravine median | p90 | max | >=4 | >=8 | slivers/tr | 1-block ledges/tr | odd step gaps/tr | rise4 p90 | rise4 max |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for lab, s, e in rows:
        if not s["n"]:
            print(f"| {lab} | 0 |")
            continue
        print(f"| {lab} | {s['n']} | {s['median']:.1f} | {s['p90']:.1f} | {s['max']:.0f} | {100 * s['fracGE4']:.0f}% | "
              f"{100 * s['fracGE8']:.0f}% | {e['slivers']:.2f} | {e['ledges']:.2f} | "
              f"{(e['oddGapsPerTransect'] if e['oddGapsPerTransect'] is not None else float('nan')):.2f} | "
              f"{s['rise4p90']:.0f} | {s['rise4max']:.0f} |")


if __name__ == "__main__":
    main()
