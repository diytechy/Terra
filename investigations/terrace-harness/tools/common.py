"""Shared helpers for the terrace harness."""
import json
import os

import numpy as np

LAB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_raster(dirpath, name):
    meta = json.load(open(os.path.join(dirpath, name + ".json")))
    data = np.fromfile(os.path.join(dirpath, name + ".u16"), dtype="<u2").reshape(meta["height"], meta["width"])
    return meta, data


def load_families(path=None):
    return json.load(open(path or os.path.join(LAB, "data", "families.json")))


def family_lut(ids, fam):
    """Arrays over a raster's ID table: template name per index, terraced flag per index."""
    tmpl = [fam["biomes"].get(i, {}).get("template") or "?" for i in ids]
    terr = np.array([fam["biomes"].get(i, {}).get("terraced", False) for i in ids], dtype=bool)
    return tmpl, terr
