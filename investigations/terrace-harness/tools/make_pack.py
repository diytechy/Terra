"""Zip a CHIMERA checkout into a Terra pack using the same allowlist as ORIGEN2's .scripts/pack.sh.

Usage: python make_pack.py <ORIGEN2 checkout> <output zip>
"""
import os
import sys
import zipfile

ALLOW = ["pack.yml", "meta.yml", "customization.yml", "substratum_meta.yml",
         "biomes", "biome-distribution", "features", "palettes", "math", "structures"]


def main(src, dst):
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    n = 0
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for item in ALLOW:
            p = os.path.join(src, item)
            if os.path.isfile(p):
                z.write(p, item)
                n += 1
                continue
            for root, dirs, files in os.walk(p):
                dirs[:] = [d for d in dirs if not d.startswith(".")]
                for f in files:
                    if f.startswith("."):
                        continue
                    full = os.path.join(root, f)
                    z.write(full, os.path.relpath(full, src).replace(os.sep, "/"))
                    n += 1
    print(f"wrote {dst} ({n} files)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
