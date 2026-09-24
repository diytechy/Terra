"""Compare two generated versions of one window: height differences in biome interiors vs near borders.

Usage: python compare_windows.py <genA window dir> <genB window dir> <originChunkX> <originChunkZ> <chunks>
Interior = columns > 18 blocks from any biome border (outside every blend stencil).
"""
import os
import sys

import numpy as np
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anvil import load_heightmap  # noqa: E402
from common import load_raster  # noqa: E402

a_dir, b_dir, ox, oz, n = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
x0, z0, size = ox * 16, oz * 16, n * 16
a = load_heightmap(os.path.join(a_dir, "region"), x0, z0, size, size)[0]
b = load_heightmap(os.path.join(b_dir, "region"), x0, z0, size, size)[0]
_, lab = load_raster(a_dir, "biomes")
edge = np.zeros(lab.shape, bool)
edge[:, 1:] |= lab[:, 1:] != lab[:, :-1]
edge[1:, :] |= lab[1:, :] != lab[:-1, :]
d = ndimage.distance_transform_edt(~edge)
inner = d > 18
diff = np.abs(a - b)
print(f"interior columns: {inner.sum()}, identical {100 * (diff[inner] == 0).mean():.2f}%, max diff {diff[inner].max()}")
print(f"border-zone columns: {(~inner).sum()}, identical {100 * (diff[~inner] == 0).mean():.2f}%, "
      f"mean |diff| {diff[~inner].mean():.2f}, max {diff[~inner].max()}")
