"""Compare the same chunk area in two Java worlds block-for-block (lab tool; determinism checks).

Usage: python compare_java_area.py WORLD_A WORLD_B MIN_CX MIN_CZ MAX_CX MAX_CZ
Prints chunks compared, chunks differing, differing blocks/biome cells/top heights; exit 1 if any differ.
"""
import collections
import re

FEATURE = re.compile(r'(leaves|log|wood|vine|sapling|mushroom|flower|grass|fern|bush|kelp|seagrass|dripleaf|moss|azalea|bamboo|cactus|sugar_cane|berry|lichen|roots|propagule|snow)')
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import javaworld as jw  # noqa: E402


def region_dir(w):
    d = os.path.join(w, "dimensions", "minecraft", "overworld", "region")
    return d if os.path.isdir(d) else os.path.join(w, "region")


def main():
    a, b = sys.argv[1], sys.argv[2]
    x0, z0, x1, z1 = map(int, sys.argv[3:7])
    want = {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}
    ca = jw.read_world_chunks(region_dir(a), want)
    cb = jw.read_world_chunks(region_dir(b), want)
    stats = collections.Counter()
    pairs = collections.Counter()
    nonfeat = collections.Counter()
    for k in sorted(want):
        A, B = ca.get(k), cb.get(k)
        if not A or not B or not A.status.endswith("full") or not B.status.endswith("full"):
            stats["not_full_in_both"] += 1
            continue
        stats["chunks"] += 1
        diff = False
        for x in range(16):
            for z in range(16):
                if A.top_solid(x, z) != B.top_solid(x, z):
                    stats["top_height_diff"] += 1
                    diff = True
                for y in range(-64, 320):
                    ba, bb = A.block(x, y, z), B.block(x, y, z)
                    if ba != bb:
                        stats["block_diff"] += 1
                        if not (FEATURE.search(ba) or FEATURE.search(bb)):
                            stats["non_feature_block_diff"] += 1
                            nonfeat[(ba, bb)] += 1
                        pairs[(ba, bb)] += 1
                        diff = True
                    if (y & 3) == 0 and (x & 3) == 0 and (z & 3) == 0 and A.biome(x, y, z) != B.biome(x, y, z):
                        stats["biome_cell_diff"] += 1
                        diff = True
        stats["chunks_differing"] += diff
    print(dict(stats))
    print("top block diffs:", pairs.most_common(8))
    print("top non-feature diffs:", nonfeat.most_common(8))
    sys.exit(1 if stats["chunks_differing"] else 0)


if __name__ == "__main__":
    main()
