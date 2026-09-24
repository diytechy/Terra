"""Hillshade the same window under several labels, side by side, with biome borders.

Usage: python side_by_side.py <area> <window> <out.png> <label> [<label> ...] [--crop x0,z0,size]
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anvil import load_heightmap  # noqa: E402
from common import LAB, load_raster  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("area")
    ap.add_argument("window")
    ap.add_argument("out")
    ap.add_argument("labels", nargs="+")
    ap.add_argument("--crop", default=None)
    a = ap.parse_args()
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import LightSource
    w = next(x for x in json.load(open(os.path.join(LAB, "data", a.area, "windows.json")))["windows"] if x["name"] == a.window)
    x0, z0, size = w["originChunkX"] * 16, w["originChunkZ"] * 16, w["chunks"] * 16
    tops = [load_heightmap(os.path.join(LAB, "data", "gen", l, a.area, a.window, "region"), x0, z0, size, size)[0]
            for l in a.labels]
    _, lab = load_raster(os.path.join(LAB, "data", "gen", a.labels[0], a.area, a.window), "biomes")
    sl = (slice(None), slice(None))
    ext = (x0, x0 + size, z0 + size, z0)
    if a.crop:
        cx, cz, cs = map(int, a.crop.split(","))
        sl = (slice(cz - z0, cz - z0 + cs), slice(cx - x0, cx - x0 + cs))
        ext = (cx, cx + cs, cz + cs, cz)
    lo = min(t[sl].min() for t in tops) - 10
    hi = max(t[sl].max() for t in tops) + 5
    ls = LightSource(azdeg=315, altdeg=40)
    fig, axs = plt.subplots(1, len(tops), figsize=(6 * len(tops), 6.4))
    edge = np.zeros(lab.shape, bool)
    edge[:, 1:] |= lab[:, 1:] != lab[:, :-1]
    edge[1:, :] |= lab[1:, :] != lab[:-1, :]
    for ax, t, l in zip(np.atleast_1d(axs), tops, a.labels):
        rgb = ls.shade(t[sl].astype(float), cmap=plt.cm.terrain, vert_exag=1.5, blend_mode="soft", vmin=lo, vmax=hi)
        ax.imshow(rgb, extent=ext, interpolation="nearest")
        ov = np.zeros(edge[sl].shape + (4,))
        ov[edge[sl]] = (0, 0, 0, 0.8)
        ax.imshow(ov, extent=ext, interpolation="nearest")
        cs = ax.contour(np.arange(ext[0], ext[1]), np.arange(ext[3], ext[2]), t[sl], levels=np.arange(lo, hi, 4),
                        colors="k", linewidths=0.25, alpha=0.5)
        ax.set_title(f"{l}  {a.window} {w['pair']}", fontsize=8)
    fig.tight_layout()
    fig.savefig(a.out, dpi=100)


if __name__ == "__main__":
    main()
