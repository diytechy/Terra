# Terra Caching & Generation Pipeline — Performance Reference

Generated from investigation session. Covers all active caches, their scopes, and the full
sequence of events from pack load through feature placement.

---

## Cache Inventory

### World-scoped (shared across all chunks, persist for server lifetime)

| Cache | Class | Key | Eviction |
|---|---|---|---|
| Chunk Sampler3D | `SamplerProvider` | (chunkX, chunkZ, seed, minH, maxH) | Caffeine LRU, ~256 entries |
| Biome chunk (pipeline) | `PipelineBiomeProvider` | (chunkWorldX, chunkWorldZ, seed) | Caffeine LRU, 256 entries |
| Image sampler | `ImageCache` | file path string | Optional expire-after-access, unlimited size |

### Thread-scoped (per-thread, survive across chunks)

| Cache | Class | Key | Eviction |
|---|---|---|---|
| 2D biome lookup | `CachingBiomeProvider` | (x, z, seed) | Caffeine LRU, 256 — **opt-in**, `cache.biome.enable: true` |
| 3D biome lookup | `CachingBiomeProvider` | (x, y, z, seed) | Caffeine LRU, ~981,504 — **opt-in**, off by default |
| Named 2D pack sampler | `LastValueSampler` | (seed, x, z) direct compare | 1-slot FIFO per sampler instance |
| Named multi-hit 2D (cellular lookup) | `MultiSlotCacheSampler` | (seed, xBits, zBits) | 8-slot round-robin per instance |
| Explicit `CACHE` type sampler (2D) | `CacheSampler` | Direct-mapped hash (XZ) | Direct-mapped, default 4096 slots (~64 KB) |
| Explicit `CACHE` type sampler (3D) | `CacheSampler` | Direct-mapped hash (XYZ) | Direct-mapped, default 131072 slots (~1 MB) |
| ChunkInterpolator blend map | `BiomeWeightMap` (ThreadLocal) | Reused struct per (x,z) center | Explicit `reset()` per XZ column |
| ElevationInterpolator array pool | `ElevationInterpolator` (ThreadLocal) | Array reuse | Grown on demand |
| Pipeline biome work buffers | `BiomeChunkImpl` (ThreadLocal) | `BIOMES_BUF` / `LOOKUP_BUF` arrays | Grown on demand |

### Chunk-scoped (created per chunk, discarded when done)

| Cache | Class | Key | Notes |
|---|---|---|---|
| Pipeline sampler context | `ChunkGenerationContext` | (worldX, worldZ) → flat double[][] | NaN sentinel, reset between pipeline chunks |
| Pipeline sampler wrapper | `ChunkScopedCacheSampler` | Slot index → context | Invalidated between chunks |
| Carving sampler lookup | `LazilyEvaluatedInterpolator` | Biome instance (identity) → Sampler | `IdentityHashMap`, discarded with interpolator |

---

## Sampler Wrapping Rules (Pack Load)

Applied once at pack load, permanent for server lifetime:

- **All named 2D pack-level samplers** (`samplers:` at pack level) → wrapped in `LastValueSampler`
  via `NoiseAddon` lines 174-179.
- **Inline 2D samplers** in biome-level `samplers:` blocks → also wrapped in `LastValueSampler`
  via `DeferredExpressionSampler.compile()` (fix on `TerrainInvestigation` branch).
- **`CELLULAR` sampler NoiseLookup** → automatically wrapped in `MultiSlotCacheSampler`
  (8-slot round-robin) by `CellularNoiseTemplate`, regardless of whether named or anonymous.
- **Explicit `type: CACHE`** in YAML → `CacheSampler` (2D or 3D, configurable size).
- **3D samplers** (inline or named) → no automatic caching.
- **`CachingBiomeProvider`** (2D+3D Caffeine biome cache) → only active when `cache.biome.enable: true`
  in pack.yml; defaults `false`.

---

## Full Generation Sequence

### Phase 1 — Pipeline Biome Chunk (triggered on first biome query for any position)

Scope: **pipeline big chunk** (e.g. 32 slots × 4 block resolution = 128 block coverage).
This is NOT a Minecraft 16×16 chunk.

1. `PipelineBiomeProvider.biomeChunkCache` misses → triggers `PipelineImpl.generateChunk()`.
2. Thread-local `ChunkGenerationContext` reset for this big chunk's block origin.
3. Pipeline stages walk the `arraySize×arraySize` working grid (includes expansion border).
4. Samplers wrapped in `ChunkScopedCacheSampler` read/write the active context's flat `double[][]`
   arrays — cache hit = field read; miss = evaluate and store.
5. `LastValueSampler` on named samplers absorbs repeated hits at the same (x,z) within a stage sweep.
6. Working array compacted into `chunkSize×chunkSize` result; context invalidated.
7. Resulting `BiomeChunk` stored in `biomeChunkCache` (world-scoped, 256 entries LRU).

**Key**: `ChunkScopedCacheSampler` scope is the **pipeline big chunk**, not a Minecraft chunk.
`LazilyEvaluatedInterpolator`'s `IdentityHashMap` carving cache is scoped to a **Minecraft 16×16 chunk**.

### Phase 2 — Sampler3D Construction (once per Minecraft 16×16 chunk)

Called from `NoiseChunkGenerator3D.generateChunkData()` via `SamplerProvider.getChunk()`.

1. `SamplerProvider` Caffeine cache checked (key: chunkX, chunkZ, seed, minH, maxH).
2. On miss: `Sampler3D` is built:
   - `ElevationInterpolator`: samples elevation sampler over (18 + 2·smooth)² grid around chunk.
     Uses thread-local array pool to avoid allocation.
   - `ChunkInterpolator`: builds 5×5×(height/4) sparse noise grid:
     - Pre-scan 25 center columns: `provider.getColumnForTerrain()` → `biomeChunkCache` hit.
       Surface biome's blend settings determine `localMaxBlend` (sizes the columns array).
     - For each of 25 (x,z) sparse centers:
       - Build **neighbor-only blend map** once per (x,z) using `getSurface()` — zero extrusion cost.
       - For each Y sparse level: get center biome via `biomeColumn.get(scaledY)` — one extrusion call.
         Center noise contribution added to neighbor-weighted sum inline.
     - Thread-local `BiomeWeightMap` reused across all centers (explicit `reset()` per XZ).
3. Completed `Sampler3D` stored in `SamplerProvider` cache.
4. `LazilyEvaluatedInterpolator` created for carving (chunk-scoped, discarded after placement).

### Phase 3 — Block Palette Placement

`generateChunkData()` block loop: x (0→15), z (0→15), y (maxHeight→minHeight descending).

- **`biomeProvider.getColumn(cx, cz, world)`** — called **256×/chunk** (once per XZ column).
  - `BiomeExtrusionProvider.getColumn()` always uses `true, true` warp flags (palette always warps).
  - `delegate.getBaseBiome(cx, cz, seed)` → `PipelineBiomeProvider.getBiome()`:
    applies mutator noise warp, divides by resolution, hits `biomeChunkCache`.
  - Returns `BaseBiomeColumn(base, blendedX, blendedZ)` with XZ warp pre-computed once.
- **`sampler.sample(x, y, z)`** — pure trilinear interpolation from pre-built sparse grid.
  **Zero sampler re-evaluation. Zero biome queries.** Density is fully pre-computed.
- **`biomeColumn.get(y)`** — called **98,304×/chunk** (256 columns × ~384 Y levels).
  - `BaseBiomeColumn.get(y)`: computes `blendedY` via `yBlendSampler`, calls `pipeline.extrude()`.
  - `pipeline.extrude()`: bytecode-generated chain of `Extrusion.extrude()` calls.
    Each extrusion checks biome tag via `TriStateIntCache[biome.intId]` (O(1) array), Y range,
    then samples noise for replacement selection. **No `biomeChunkCache` hit inside `extrude()`.**
  - Used for **palette selection only**, not for density.

### Phase 4 — Feature Generation Stages

Separate `populate()` calls after block placement is complete. One call per stage.

```
for chunkX in 0..15 step resolution      // resolution cells (e.g. 4×4)
  for chunkZ in 0..15 step resolution
    blendedColumn = biomeProvider.getColumn(tx + blendOffset, tz + blendOffset, world)
    blendedColumn.forRanges(resolution) → callback(yMin, yMax, biome):
      // Y walked in steps of resolution; callback fires on biome change
      for subX, subZ in resolution cell:
        locator.getSuitableCoordinates(column.clamp(yMin, yMax))
          → structure.generate(x, y, z)
```

- `forRanges()` calls `col.get(y)` once per `resolution` Y steps — far fewer extrusions than palette.
- `BiomePipelineColumn.forRanges()` fires exactly once (same biome all Y — zero loop overhead).
- Feature locators (`TerrainSurfaceLocator`, `TopTerrainSurfaceLocator`) call
  `generator.samplerProvider().get(x, z, world, biomeProvider)` → **`SamplerProvider` cache hit**
  (chunk was already built in Phase 2). Then `sampler.sample()` = interpolation, no recomputation.
- TerraScript `check()` function uses the same `samplerProvider()` path.

---

## getColumn() vs getColumnForTerrain()

Two separate factory paths on `BiomeProvider`; produce separate `BaseBiomeColumn` instances
with different warp flags — they never share state.

| | `getColumn()` | `getColumnForTerrain()` |
|---|---|---|
| Called by | `generateChunkData` (palette), feature stages | `ChunkInterpolator` (terrain noise) |
| XZ warp | Always applied (`blendedX/Z = x/z + xzBlendSampler offset`) | Controlled by `blend.terrain-coordinate-noise-warp` |
| Y warp | Always applied (`blendedY = y + yBlendSampler offset`) | Controlled by `y-blend.terrain-coordinate-noise-warp` |
| Default `BiomeProvider` fallback | — | Delegates to `getColumn()` |
| `BiomeExtrusionProvider` | `new BaseBiomeColumn(..., true, true)` | `new BaseBiomeColumn(..., terrainWarpXZ, terrainWarpY)` |

### YAML Configuration (per biome distribution preset, e.g. CHIMERA.yml)

```yaml
biomes:
  type: EXTRUSION

  blend:
    terrain-coordinate-noise-warp: false   # disable XZ warp for terrain noise queries only
    amplitude: 3
    sampler:
      type: OPEN_SIMPLEX_2
      frequency: 0.05

  y-blend:
    terrain-coordinate-noise-warp: false   # disable Y warp for terrain noise queries only
    amplitude: 2
    sampler:
      type: EXPRESSION
      expression: noisebase(x,z)+noise(x,z)*2
```

Both default `true` (existing behavior). When `false`, `ChunkInterpolator`'s biome queries
use raw (x, y, z) for extrusion — no sampler-driven coordinate warp. Palette and feature
biome queries are completely unaffected; their `BaseBiomeColumn` instances always use `true, true`.

---

## BiomePipelineColumn vs BaseBiomeColumn

Two column implementations with very different `get(y)` costs:

**`BiomePipelineColumn`** (no extrusion active):
- Constructor calls `getBiome(x, 0, z, seed)` once → `biomeChunkCache` hit.
- `get(y)` returns stored `biome` field — **O(1), zero work**.
- `forRanges()` fires callback exactly once (same biome all Y).

**`BaseBiomeColumn`** (extrusion active):
- Constructor calls `getBaseBiome(x, z, seed)` once → `biomeChunkCache` hit.
  Pre-computes `blendedX/Z` from `xzBlendSampler`.
- `get(y)` computes `blendedY` per call + `pipeline.extrude(base, blendedX, blendedY, blendedZ, seed)`.
  Full extrusion chain runs per Y — **unavoidable with extrusion active**.
- `getSurface()` returns `base` field directly — **free, bypasses extrusion entirely**.
  Used by ChunkInterpolator for neighbor blend map (zero extrusion cost for all neighbors).

---

## Key Performance Numbers (default -64→320 world, extrusion active)

| Operation | Count per Minecraft chunk | Cache / cost |
|---|---|---|
| `getColumn()` calls (palette) | 256 | `biomeChunkCache` hit (warm) |
| `biomeColumn.get(y)` calls (palette) | ~98,304 | `pipeline.extrude()` — no cache, unavoidable |
| `sampler.sample()` calls (density) | ~98,304 | Pure trilinear interpolation — no samplers |
| Sparse noise evaluations (ChunkInterpolator) | 5×5×(height/4) ≈ 2,400 | `LastValueSampler` on 2D subsamplers |
| `getColumnForTerrain()` calls (ChunkInterpolator) | 25 center + up to (2·blend+1)²×25 neighbors | `biomeChunkCache` hit |
| `biomeColumn.get(scaledY)` — center (ChunkInterpolator) | 25×(height/4) ≈ 2,400 | `pipeline.extrude()` — no cache |
| `biomeColumn.getSurface()` — neighbors (ChunkInterpolator) | up to 48×25 = 1,200 | Field read — free |

The **98,304 per-block extrusion calls** (palette phase) are the dominant biome provider cost
per chunk. Each call hits `pipeline.extrude()` — a bytecode-generated chain of `Extrusion.extrude()`
instances. Fast per call (O(1) tag check + Y range + optional noise sample) but scales directly
with world height × chunk area. The `biomeChunkCache` is not involved inside `extrude()`.

---

## Active Branches

| Branch | Description |
|---|---|
| `TerrainInvestigation` | `LastValueSampler` wrapping for inline biome-level 2D samplers in `DeferredExpressionSampler.compile()`. Misc investigation notes. |
| `Interpolation-Fix` | ChunkInterpolator blend regression fix: neighbor-only blend map built once per XZ from `getSurface()`; center uses per-Y `biomeColumn.get(scaledY)`. Added `getSurface()` to `Column<T>` API with `BaseBiomeColumn` override returning `base` directly (bypasses extrusion). |
| `main` (current) | `terrain-coordinate-noise-warp` implementation: `getColumnForTerrain()` on `BiomeProvider` API; `BiomeExtrusionProvider` overrides to produce `BaseBiomeColumn` instances with configurable XZ/Y warp suppression for terrain noise queries only. |
