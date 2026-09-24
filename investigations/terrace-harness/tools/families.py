"""Resolve which terrain template (family) every CHIMERA biome uses, and which families are terraced.

A biome's terrain sampler comes from the first config in its inheritance chain (itself first, then
`extends` in list order, depth-first) that defines `terrain.sampler`. That matches Tectonic's
"first parent wins" rule and ORIGEN2's calculate_biome_percentages.py (TerrainParent column).

Usage: python families.py <ORIGEN2 checkout> <out.json>
"""
import json
import os
import re
import sys

import yaml

# Terrace families: templates whose terrain expression builds steps. Keyed by template ID;
# the value is a short description of the step mechanism, used in reports.
TERRACED = {
    "EQ_MULTI_TERRACED_LAND": "3 nested terraceParalinear passes, noise offsets, depth lerped by input",
    "EQ_ROCKY_BUTTES": "2 masked terraceStrata systems (deposits + buttes), max() combined",
    "EQ_STRATIFIED_LAND": "3 nested terraceStrata (50/30/15 blocks) on a warped height map",
    "EQ_TRAVERTINE_TERRACES": "cellular pools (max of 2 cell lookups), domain warped; no step function",
    "EQ_SPOT_SINKHOLE": "spot sinkhole: lerp to a flat pit floor by rim distance",
    "EQ_MESA": "no own steps; mesa steps come from the global elevation (elevation_with_mesas)",
    "EQ_TERRACED_MOUNTAINS": "3 nested terraceParabolic (10/20/30) on a cellular mountain shape",
    "EQ_TERRACE_MOUNTAINS": "round()-based terrace of a mountain noise, variable terrace height",
    "EQ_BADLANDS_BALCONIES": "round()-based terrace inside a cellular terracing mask",
    "EQ_BUTTES": "legacy: 2 masked terraceStrata systems",
    "EQ_BUTTES_ARCHIPELAGO": "legacy: masked terraceStrata buttes",
}


def load_configs(root):
    configs = {}
    for base in ("biomes",):
        for dp, dn, fn in os.walk(os.path.join(root, base)):
            dn[:] = [d for d in dn if not d.startswith(".")]
            for f in fn:
                if not f.endswith((".yml", ".yaml")):
                    continue
                p = os.path.join(dp, f)
                txt = open(p, encoding="utf-8", errors="replace").read()
                try:
                    data = yaml.safe_load(txt)
                except Exception:
                    data = None
                if not isinstance(data, dict):
                    # Fall back to regex for files PyYAML cannot parse (Terra-specific merge syntax).
                    m = re.search(r"^id:\s*([A-Za-z0-9_]+)", txt, re.M)
                    if not m:
                        continue
                    ext = re.search(r"^extends:\s*(\[.*?\]|\S+)?\s*\n((?:\s*-\s*\S+\s*\n)*)", txt, re.M)
                    parents = []
                    if ext:
                        if ext.group(1):
                            parents = [s.strip(" '\"") for s in ext.group(1).strip("[]").split(",") if s.strip()]
                        parents += re.findall(r"-\s*(\S+)", ext.group(2) or "")
                    has_terrain = bool(re.search(r"^terrain:\s*\n(\s+.*\n)*?\s+sampler:", txt, re.M))
                    data = {"id": m.group(1), "extends": parents, "_terrain": has_terrain,
                            "abstract": bool(re.search(r"^abstract:\s*true", txt, re.M))}
                if "id" not in data:
                    continue
                ext = data.get("extends") or []
                if isinstance(ext, str):
                    ext = [ext]
                terrain = data.get("terrain")
                has = data.get("_terrain", isinstance(terrain, dict) and "sampler" in terrain)
                configs[data["id"]] = {"extends": [e for e in ext if isinstance(e, str)], "terrain": has,
                                       "abstract": bool(data.get("abstract")), "file": os.path.relpath(p, root)}
    return configs


def resolve(configs, bid, seen=None):
    seen = seen or set()
    if bid in seen or bid not in configs:
        return None
    seen.add(bid)
    c = configs[bid]
    if c["terrain"]:
        return bid
    for p in c["extends"]:
        r = resolve(configs, p, seen)
        if r:
            return r
    return None


def main(root, out):
    configs = load_configs(root)
    biomes = {}
    for bid, c in configs.items():
        if c["abstract"]:
            continue
        t = resolve(configs, bid)
        biomes[bid] = {"template": t, "terraced": t in TERRACED, "file": c["file"]}
    fams = {}
    for bid, b in biomes.items():
        fams.setdefault(b["template"], []).append(bid)
    json.dump({"terracedFamilies": TERRACED, "biomes": biomes,
               "families": {k or "None": sorted(v) for k, v in sorted(fams.items(), key=lambda kv: str(kv[0]))}},
              open(out, "w"), indent=1)
    n_ter = sum(1 for b in biomes.values() if b["terraced"])
    print(f"{len(biomes)} concrete biomes, {n_ter} terraced, {len(fams)} templates -> {out}")
    for t in TERRACED:
        print(f"  {t}: {len(fams.get(t, []))}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
