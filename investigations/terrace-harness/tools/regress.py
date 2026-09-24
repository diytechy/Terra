"""Pass/fail regression check on a measured label (terrace-blending.md section 5, step 4).

Limits are relative to the same run's in-biome controls, so they hold across seeds and areas:
  ravine>=4 share   <= controls share + 5 percentage points
  ravine>=8 share   <= controls share + 3 percentage points
  slivers/transect  <= 2 x controls
  rise4 p90         <= controls p90 + 6 blocks        (no new cliffs)
Exit code 1 when any limit fails; prints the failing windows (their PNGs are in reports/<label>/<area>/).

Usage: python regress.py <label> [--areas a,b]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import extra, load, stats  # noqa: E402


def check(trs, ctl):
    s, c, e, ec = stats(trs), stats(ctl), extra(trs), extra(ctl)
    return [
        ("ravine>=4 share", s["fracGE4"], c["fracGE4"] + 0.05),
        ("ravine>=8 share", s["fracGE8"], c["fracGE8"] + 0.03),
        ("slivers/transect", e["slivers"], 2 * ec["slivers"]),
        ("rise4 p90", s["rise4p90"], c["rise4p90"] + 6),
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--areas", default=None)
    a = ap.parse_args()
    res = load(a.label, set(a.areas.split(",")) if a.areas else None)
    trs = [t for r in res for t in r["transects"]]
    ctl = [t for r in res for t in r["controls"]]
    ok = True
    print(f"Regression check `{a.label}`: {len(trs)} border transects, {len(ctl)} controls")
    for name, v, lim in check(trs, ctl):
        passed = v <= lim
        ok &= passed
        print(f"  {'PASS' if passed else 'FAIL'}  {name}: {v:.3f} (limit {lim:.3f})")
    if not ok:
        bad = sorted(((sum(t["ravine"] >= 8 for t in r["transects"]), r["area"], r["window"]) for r in res), reverse=True)
        print("  windows with most ravines >= 8:", ", ".join(f"{a_}/{w} ({n})" for n, a_, w in bad[:8] if n))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
