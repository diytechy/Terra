"""Minimal reader for modern (1.18+) Anvil region files: top solid block per column.

Only what the terrace harness needs: it parses the NBT of each chunk, unpacks the
block-state palettes of each section with numpy, and returns per-column
  top      highest solid block Y (int16, world coordinates; minY-1 if none)
  fluidTop highest water/lava Y above the top solid block (or top if none)
  topName  palette name of the top solid block (index into a name table)
"""
import os
import re
import struct
import zlib

import numpy as np

# Blocks that are not "ground" for the heightmap. Terrain-only generation (no features) produces
# almost only stone/soil/fluid, so this list is short on purpose.
NON_SOLID = {
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water", "minecraft:lava",
    "minecraft:snow", "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
    "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant",
    "minecraft:bubble_column", "minecraft:light",
}
FLUIDS = {"minecraft:water", "minecraft:lava", "minecraft:bubble_column"}


# ---------------------------------------------------------------- NBT
def _read_nbt(buf):
    pos = 0

    def u8():
        nonlocal pos
        v = buf[pos]
        pos += 1
        return v

    def i16():
        nonlocal pos
        v = struct.unpack_from(">h", buf, pos)[0]
        pos += 2
        return v

    def i32():
        nonlocal pos
        v = struct.unpack_from(">i", buf, pos)[0]
        pos += 4
        return v

    def string():
        nonlocal pos
        n = struct.unpack_from(">H", buf, pos)[0]
        pos += 2
        s = buf[pos:pos + n].decode("utf-8", "replace")
        pos += n
        return s

    def payload(t):
        nonlocal pos
        if t == 1:
            v = struct.unpack_from(">b", buf, pos)[0]; pos += 1; return v
        if t == 2:
            return i16()
        if t == 3:
            return i32()
        if t == 4:
            v = struct.unpack_from(">q", buf, pos)[0]; pos += 8; return v
        if t == 5:
            v = struct.unpack_from(">f", buf, pos)[0]; pos += 4; return v
        if t == 6:
            v = struct.unpack_from(">d", buf, pos)[0]; pos += 8; return v
        if t == 7:
            n = i32(); v = bytes(buf[pos:pos + n]); pos += n; return v
        if t == 8:
            return string()
        if t == 9:
            et = u8(); n = i32()
            return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u8()
                if tt == 0:
                    return d
                name = string()
                d[name] = payload(tt)
        if t == 11:
            n = i32(); v = np.frombuffer(buf, dtype=">i4", count=n, offset=pos); pos += 4 * n; return v
        if t == 12:
            n = i32(); v = np.frombuffer(buf, dtype=">i8", count=n, offset=pos); pos += 8 * n; return v
        raise ValueError(f"bad tag {t}")

    t = u8()
    string()
    return payload(t)


def read_region(path):
    """Yield (chunkX, chunkZ, nbt_root) for every chunk in the region file."""
    with open(path, "rb") as f:
        data = f.read()
    for i in range(1024):
        loc = struct.unpack_from(">I", data, i * 4)[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0 or cnt == 0:
            continue
        start = off * 4096
        length = struct.unpack_from(">I", data, start)[0]
        ctype = data[start + 4]
        raw = data[start + 5:start + 4 + length]
        if ctype == 2:
            raw = zlib.decompress(raw)
        elif ctype == 1:
            import gzip
            raw = gzip.decompress(raw)
        elif ctype != 3:
            raise ValueError(f"unsupported compression {ctype}")
        root = _read_nbt(memoryview(raw).tobytes())
        yield root["xPos"], root["zPos"], root


def _unpack(data, bits, count=4096):
    """Unpack non-spanning packed longs (1.16+) to an int array of length count."""
    per = 64 // bits
    longs = data.astype(np.uint64)
    shifts = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    mask = np.uint64((1 << bits) - 1)
    vals = (longs[:, None] >> shifts[None, :]) & mask
    return vals.reshape(-1)[:count].astype(np.int32)


def chunk_columns(root, names):
    """Return (top, fluidTop, topNameIdx) 16x16 arrays [z, x] for one chunk. names: shared name->idx dict."""
    sections = sorted(root["sections"], key=lambda s: s["Y"])
    top = np.full((16, 16), -10_000, dtype=np.int32)
    fluid = np.full((16, 16), -10_000, dtype=np.int32)
    tname = np.full((16, 16), -1, dtype=np.int32)
    done = np.zeros((16, 16), dtype=bool)
    for sec in reversed(sections):
        bs = sec.get("block_states")
        if not bs:
            continue
        pal = [p["Name"] for p in bs["palette"]]
        if len(pal) == 1:
            idx = np.zeros(4096, dtype=np.int32)
        else:
            bits = max(4, (len(pal) - 1).bit_length())
            idx = _unpack(bs["data"], bits)
        idx = idx.reshape(16, 16, 16)  # [y, z, x]
        solid_lut = np.array([p not in NON_SOLID for p in pal])
        fluid_lut = np.array([p in FLUIDS for p in pal])
        name_lut = np.array([names.setdefault(p, len(names)) for p in pal], dtype=np.int32)
        solid = solid_lut[idx]
        isfl = fluid_lut[idx]
        base = sec["Y"] * 16
        for y in range(15, -1, -1):
            wy = base + y
            fl = isfl[y] & (fluid == -10_000) & ~done
            fluid[fl] = wy
            s = solid[y] & ~done
            if s.any():
                top[s] = wy
                tname[s] = name_lut[idx[y][s]]
                done |= s
        if done.all():
            break
    fluid = np.where(fluid == -10_000, top, fluid)
    return top, fluid, tname


def load_heightmap(region_dir, x0, z0, width, depth):
    """Assemble top/fluid/topName rasters [z, x] for the block rectangle [x0, x0+width) x [z0, z0+depth)."""
    top = np.full((depth, width), -10_000, dtype=np.int32)
    fluid = np.full((depth, width), -10_000, dtype=np.int32)
    tname = np.full((depth, width), -1, dtype=np.int32)
    names = {}
    for fn in os.listdir(region_dir):
        if not re.match(r"r\.-?\d+\.-?\d+\.mca$", fn):
            continue
        for cx, cz, root in read_region(os.path.join(region_dir, fn)):
            bx, bz = cx * 16 - x0, cz * 16 - z0
            if bx + 16 <= 0 or bz + 16 <= 0 or bx >= width or bz >= depth:
                continue
            t, f, n = chunk_columns(root, names)
            sx0, sz0 = max(0, -bx), max(0, -bz)
            ex, ez = min(16, width - bx), min(16, depth - bz)
            top[bz + sz0:bz + ez, bx + sx0:bx + ex] = t[sz0:ez, sx0:ex]
            fluid[bz + sz0:bz + ez, bx + sx0:bx + ex] = f[sz0:ez, sx0:ex]
            tname[bz + sz0:bz + ez, bx + sx0:bx + ex] = n[sz0:ez, sx0:ex]
    inv = {v: k for k, v in names.items()}
    return top, fluid, tname, [inv[i] for i in range(len(inv))]
