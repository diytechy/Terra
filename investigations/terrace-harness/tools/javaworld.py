"""Read Java Edition (Anvil, 1.18+ / 26.x) region files: blocks, biomes, heights (lab tool).

Pure reading only. Chunk layout (1.18+): top-level 'sections' list, each with
'Y', 'block_states' {palette, data} and 'biomes' {palette, data}; packed longs
never span entries (1.16+ packing).
"""
import io
import math
import os
import struct
import zlib
import gzip

import nbtlib

AIRS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def _unpack(data, bits, count):
    """Unpack `count` indices of `bits` width from a list of signed longs (no spanning)."""
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for i in range(count):
        word = int(data[i // per_long]) & 0xFFFFFFFFFFFFFFFF
        out.append((word >> ((i % per_long) * bits)) & mask)
    return out


class JavaChunk:
    def __init__(self, nbt):
        self.nbt = nbt
        self.cx = int(nbt["xPos"])
        self.cz = int(nbt["zPos"])
        self.status = str(nbt.get("Status", ""))
        self.sections = {}
        for sec in nbt.get("sections", []):
            y = int(sec["Y"])
            bs = sec.get("block_states")
            bio = sec.get("biomes")
            blocks = None
            if bs is not None:
                pal = [str(p["Name"]) for p in bs["palette"]]
                if "data" in bs and len(pal) > 1:
                    bits = max(4, math.ceil(math.log2(len(pal))))
                    idx = _unpack(bs["data"], bits, 4096)
                    blocks = [pal[i] for i in idx]
                else:
                    blocks = [pal[0]] * 4096
            biomes = None
            if bio is not None:
                bpal = [str(p) for p in bio["palette"]]
                if "data" in bio and len(bpal) > 1:
                    bits = max(1, math.ceil(math.log2(len(bpal))))
                    idx = _unpack(bio["data"], bits, 64)
                    biomes = [bpal[i] for i in idx]
                else:
                    biomes = [bpal[0]] * 64
            self.sections[y] = (blocks, biomes)

    def block(self, x, y, z):
        """Block name at chunk-local x,z (0..15) and world y."""
        sec = self.sections.get(y >> 4)
        if not sec or sec[0] is None:
            return "minecraft:air"
        return sec[0][((y & 15) << 8) | (z << 4) | x]

    def biome(self, x, y, z):
        sec = self.sections.get(y >> 4)
        if not sec or sec[1] is None:
            return None
        return sec[1][(((y & 15) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)]

    def top_solid(self, x, z, min_y=-64, max_y=319, ignore=AIRS):
        for y in range(max_y, min_y - 1, -1):
            if self.block(x, y, z) not in ignore:
                return y
        return None

    def structures(self):
        s = self.nbt.get("structures", {})
        return {"starts": list(s.get("starts", {}).keys()), "references": list(s.get("References", {}).keys())}


def read_region(path):
    """Yield JavaChunk objects from one .mca file."""
    with open(path, "rb") as f:
        buf = f.read()
    if len(buf) < 8192:
        return
    for i in range(1024):
        off = struct.unpack(">I", b"\x00" + buf[i * 4:i * 4 + 3])[0]
        cnt = buf[i * 4 + 3]
        if off == 0 or cnt == 0:
            continue
        start = off * 4096
        length = struct.unpack(">I", buf[start:start + 4])[0]
        ctype = buf[start + 4]
        payload = buf[start + 5:start + 4 + length]
        if ctype == 2:
            raw = zlib.decompress(payload)
        elif ctype == 1:
            raw = gzip.decompress(payload)
        elif ctype == 3:
            raw = payload
        else:
            raise ValueError(f"unsupported chunk compression {ctype} in {path}")
        nbt = nbtlib.File.parse(io.BytesIO(raw))
        yield JavaChunk(nbt)


def read_world_chunks(region_dir, want=None):
    """Return {(cx,cz): JavaChunk} for all (or wanted) chunks in a region directory."""
    out = {}
    for name in os.listdir(region_dir):
        if not name.endswith(".mca"):
            continue
        _, rx, rz, _ = name.split(".")
        rx, rz = int(rx), int(rz)
        if want is not None and not any((cx >> 5) == rx and (cz >> 5) == rz for cx, cz in want):
            continue
        for ch in read_region(os.path.join(region_dir, name)):
            if want is None or (ch.cx, ch.cz) in want:
                out[(ch.cx, ch.cz)] = ch
    return out
