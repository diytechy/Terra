# Plan: Terrain Density Debug Traces

## Context

Solid blocks appear all the way to y=320 (world top) near MESA_MONUMENTS at:
- seed: `7099699057166038826`, x: `21`, y: `300`, z: `-326`

BiomeTool does **not** show positive density at this position. The divergence means the chunk interpolation or biome blending path is producing a different result than the direct per-block query BiomeTool uses. The chunk path differs from direct query in three ways:
1. **Trilinear interpolation** between a sparse 4-block grid — values overshoot at intermediate positions
2. **Biome blending** — neighboring biomes' noise is weighted-averaged at each sparse point
3. **Float precision** — sparse storage uses `float`, not `double`

Adding compile-time-eliminated debug traces (modeled on `SamplerFloorFeature`) will let us see at each stage what density each path computes, which biomes are in the blend neighborhood, and where positive density appears at high y.

---

## Reference Coordinates

| Item | Value |
|---|---|
| World seed | `7099699057166038826L` |
| World x, z | `21`, `-326` |
| Chunk | `(1, -21)` |
| In-chunk x, z | `5`, `10` |
| Target y range | `280–320` |
| Sparse y at y=300 | exact grid point (300 − (−64)) / 4 = 91 |

---

## Files to Change

| File | Change |
|---|---|
| `common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/TerrainDebug.java` | **NEW** — compile-time flag + coordinate filter |
| `ChunkInterpolator.java` | Add traces at sparse grid construction |
| `Sampler3D.java` | Add traces at combined density |
| `NoiseChunkGenerator3D.java` | Add traces in block placement loop |

All in `common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/`.

---

## Step 1 — New file: `TerrainDebug.java`

Follows the exact pattern of `SamplerFloorFeature.java`. All `if (TerrainDebug.ENABLED)` branches are dead-code-eliminated by javac/JIT when `ENABLED = false`.

```java
package com.dfsek.terra.addons.chunkgenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerrainDebug {
    // Flip to true and recompile to enable coordinate-targeted density tracing.
    // All branches are JIT-eliminated when false — zero runtime cost.
    public static final boolean ENABLED = false;

    public static final Logger LOG = LoggerFactory.getLogger("TerrainDebug");

    // Target coordinate. CHECK_SEED = false traces any seed at this XZ.
    public static final long TARGET_SEED  = 7099699057166038826L;
    public static final boolean CHECK_SEED = true;
    public static final int  TARGET_WORLD_X = 21;
    public static final int  TARGET_WORLD_Z = -326;
    public static final int  TARGET_Y_MIN   = 280;
    public static final int  TARGET_Y_MAX   = 320;

    // Derived chunk coords — kept here so callers don't recompute.
    public static final int TARGET_CHUNK_X = Math.floorDiv(TARGET_WORLD_X, 16);  //  1
    public static final int TARGET_CHUNK_Z = Math.floorDiv(TARGET_WORLD_Z, 16);  // -21

    public static boolean isTargetChunk(int chunkX, int chunkZ) {
        return chunkX == TARGET_CHUNK_X && chunkZ == TARGET_CHUNK_Z;
    }

    public static boolean isTargetSeed(long seed) {
        return !CHECK_SEED || seed == TARGET_SEED;
    }

    private TerrainDebug() {}
}
```

---

## Step 2 — `ChunkInterpolator.java`

This is the most critical trace: it shows what the biome blend is computing at each sparse grid point inside the target chunk.

### 2a — Guard at constructor entry

After `int xOrigin = chunkX << 4;` and `int zOrigin = chunkZ << 4;` (around line 54), add:

```java
final boolean debugChunk = TerrainDebug.ENABLED
    && TerrainDebug.isTargetChunk(chunkX, chunkZ)
    && TerrainDebug.isTargetSeed(seed);
if (debugChunk) {
    TerrainDebug.LOG.info("[CI] Building ChunkInterpolator for chunk ({}, {})", chunkX, chunkZ);
}
```

### 2b — Trace per sparse-point noise computation (inside the `for y` loop, after line 219)

After `noiseStorage[x][z][y] = (float) noise;` add:

```java
if (debugChunk) {
    int absX = xOrigin + (x << 2);
    int absZ = zOrigin + (z << 2);
    int absY = scaledY;
    if (absY >= TerrainDebug.TARGET_Y_MIN && absY <= TerrainDebug.TARGET_Y_MAX) {
        // Log blend neighborhood summary
        StringBuilder blendInfo = new StringBuilder();
        for (int b = 0; b < blendMap.size; b++) {
            blendInfo.append(String.format(" [sampler@%x w=%.3f]",
                System.identityHashCode(blendMap.samplers[b]), blendMap.weights[b]));
        }
        TerrainDebug.LOG.info(
            "[CI] sparse ({},{},{}) biomes={} totalW={:.3f}{} rawNoise={:.6f} storedF={:.6f}{}",
            absX, absY, absZ,
            blendMap.size, blendMap.totalWeight, blendInfo,
            noise, noiseStorage[x][z][y],
            SamplerFloorFeature.ENABLED
                ? String.format(" floor={:.6f}", floorStorage[x][z][y])
                : "");
    }
}
```

### 2c — Trace the floor-grid presence after line 284 (`this.floorGrid = fGrid;`)

```java
if (debugChunk && SamplerFloorFeature.ENABLED) {
    TerrainDebug.LOG.info("[CI] chunk ({},{}) hasFloor={}", chunkX, chunkZ, floorGrid != null);
}
```

---

## Step 3 — `Sampler3D.java`

The `Sampler3D` is chunk-scoped but does not know its own chunk X/Z. Pass them through, or recompute from the provider call site. **Simplest approach:** add a `debugChunk` field, set in constructor.

### 3a — Add field

```java
private final boolean debugChunk;
private final int chunkX;
private final int chunkZ;
```

### 3b — Set in constructor (receives `x` = chunkX, `z` = chunkZ)

```java
this.debugChunk = TerrainDebug.ENABLED
    && TerrainDebug.isTargetChunk(x >> 4, z >> 4)  // x,z are chunk origins (xOrigin, zOrigin)
    && TerrainDebug.isTargetSeed(seed);
this.chunkX = x >> 4;
this.chunkZ = z >> 4;
```

Wait — check what `x`/`z` mean in the Sampler3D constructor. Looking at `SamplerProvider`, the `x`/`z` passed to `Sampler3D` are **chunk origins** (`chunkX << 4`, `chunkZ << 4`). So `x >> 4` recovers chunk coords.

### 3c — Trace in `sample(int x, int y, int z)` (inline coords, 0-15)

```java
public double sample(int x, int y, int z) {
    double noise3d  = interpolator.getNoise(x, y, z);
    double elev     = elevationInterpolator.getElevation(x, z);
    double density  = noise3d + elev;
    if (SamplerFloorFeature.ENABLED && interpolator.hasFloor()) {
        double floor = interpolator.getFloor(x, y, z);
        if (TerrainDebug.ENABLED && debugChunk) {
            int wy = y;  // y here is world-absolute in the hot path
            if (wy >= TerrainDebug.TARGET_Y_MIN && wy <= TerrainDebug.TARGET_Y_MAX) {
                int wx = (chunkX << 4) + x, wz = (chunkZ << 4) + z;
                if (wx == TerrainDebug.TARGET_WORLD_X && wz == TerrainDebug.TARGET_WORLD_Z) {
                    TerrainDebug.LOG.info(
                        "[S3D] ({},{},{}) noise3d={:.6f} elev={:.6f} preFloor={:.6f} floor={:.6f} final={:.6f}",
                        wx, wy, wz, noise3d, elev, density, floor, Math.max(density, floor));
                }
            }
        }
        density = Math.max(density, floor);
    } else if (TerrainDebug.ENABLED && debugChunk) {
        int wy = y;
        if (wy >= TerrainDebug.TARGET_Y_MIN && wy <= TerrainDebug.TARGET_Y_MAX) {
            int wx = (chunkX << 4) + x, wz = (chunkZ << 4) + z;
            if (wx == TerrainDebug.TARGET_WORLD_X && wz == TerrainDebug.TARGET_WORLD_Z) {
                TerrainDebug.LOG.info(
                    "[S3D] ({},{},{}) noise3d={:.6f} elev={:.6f} density={:.6f} (no floor)",
                    wx, wy, wz, noise3d, elev, density);
            }
        }
    }
    return density;
}
```

> **Note on coordinate mapping**: In the chunk generation hot path, `x` and `z` passed to `sample(int, int, int)` are **chunk-local** (0–15). `y` is **world-absolute**. Confirm this in `SamplerProvider.getChunk()` before implementing.

---

## Step 4 — `NoiseChunkGenerator3D.java`

Add to the block placement loop body, after `double density = sampler.sample(x, y, z);` (line 170) and after the minDensity floor application (line 196).

```java
if (TerrainDebug.ENABLED) {
    // isTargetChunk is false at compile time when ENABLED=false, so this whole block is eliminated.
    if (cx == TerrainDebug.TARGET_WORLD_X && cz == TerrainDebug.TARGET_WORLD_Z
            && y >= TerrainDebug.TARGET_Y_MIN && y <= TerrainDebug.TARGET_Y_MAX
            && TerrainDebug.isTargetSeed(world.getSeed())) {
        double rawDensity = sampler.sample(x, y, z);   // re-sample for logging (or cache above)
        double carverVal  = -999; // placeholder; read carver below if needed
        TerrainDebug.LOG.info(
            "[NCG] ({},{},{}) biome={} density={:.6f} minDensityFloor={} result={}",
            cx, y, cz,
            biome.getID(),
            density,
            activeFloor != null ? String.format("{:.6f}", activeFloor.getSample(world.getSeed(), cx, y, cz)) : "none",
            density > 0 ? "SOLID" : (y <= computedSea ? "WATER" : "AIR"));
    }
}
```

> **Avoid double-sampling**: since `density` is already computed at line 170, cache `rawDensity = density` before the floor application, not a second `sampler.sample()` call.

---

## Coordinate Notes for Reading the Output

- **Sparse grid points** logged by `[CI]` are at world XZ offsets from chunk origin: `(chunkX*16 + x*4, chunkZ*16 + z*4)`.
- The target block (x=21, z=-326) falls between sparse points at (20,−328), (24,−328), (20,−324), (24,−324) in world space.
- y=300 is an **exact** sparse y level (index 91 from min=−64). So the trilinear interpolation at y=300 should interpolate mostly from the y=300 sparse layer, not from adjacent layers. If the sparse value itself is positive, the blending is the cause. If it's negative but final density is positive, the floor or elevation interpolator is the cause.
- If `[CI]` shows `rawNoise` negative but `[S3D]` shows `density` positive, the culprit is `ElevationInterpolator.getElevation()` — a 2D surface biome effect leaking into underground evaluation.
- If `[CI]` shows `rawNoise` already positive at (20,300,−328) and biome count > 1, the biome blending is causing MESA_MONUMENTS' pillar sampler to bleed positive density into neighboring positions.

---

## Verification

1. Set `TerrainDebug.ENABLED = true`, rebuild Terra.
2. Generate or force-load the chunk at world (21, −326) with seed `7099699057166038826`.
3. Grep server logs for `[CI]`, `[S3D]`, `[NCG]`.
4. Confirm `[NCG]` shows `result=SOLID` at the target y range.
5. Trace backward: check `[S3D]` density at same position — if positive, floor or blend is causing it. Check `[CI]` sparse values — if positive, look at biome blend breakdown.
6. Reset `TerrainDebug.ENABLED = false` and rebuild before shipping.
