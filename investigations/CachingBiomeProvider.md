# CachingBiomeProvider — Investigation & Alternatives

## Current State

CHIMERA (ORIGEN2) does **not** use `CachingBiomeProvider`. No `cache.biome.enable` key appears in
`pack.yml`, and `ConfigPackTemplate` defaults `biomeCache` to `false`. The ternary in
`ConfigPackImpl.java:229` confirms the raw `PipelineBiomeProvider` is used with no caching wrapper.

---

## What CachingBiomeProvider Does

`CachingBiomeProvider` (`common/api/.../biome/generation/CachingBiomeProvider.java`) wraps any
`BiomeProvider` and adds two Caffeine caches:

| Cache | Key | Size | Purpose |
|---|---|---|---|
| `cache` (3D) | `SeededVector3Key(x, y, z, seed)` | 981,504 slots | Block-level biome deduplication |
| `baseCache` (2D) | `SeededVector2Key(x, z, seed)` | 256 slots | Base biome lookups |

Both caches use `CACHE_EXECUTOR` — a **single-threaded** executor shared across all Caffeine async
operations in Terra. All cache miss loads block on this executor.

---

## What ChunkScopedCacheSampler Does (and Does NOT Do)

`ChunkScopedCacheSampler` / `ChunkGenerationContext` cache sampler **noise values** during a single
pipeline chunk generation. Specifically:

- **Scope:** Active only during `PipelineImpl.generateChunk()` → `BiomeChunkImpl` constructor.
  Invalidated immediately after the pipeline chunk is stored in the Caffeine chunk cache.
- **What it caches:** The output of expensive pack samplers (e.g. `continentalDistribution`,
  `BiomeShapeLandmassValue`, `spotPlacer`) at grid coordinates within the pipeline evaluation area.
- **What it does NOT cache:** The result of `getBiome()` calls made by the chunk generator after the
  pipeline chunk is built.

These two systems do not overlap. `ChunkScopedCacheSampler` plays no role once the pipeline chunk
exists in the Caffeine chunk cache.

---

## Per-Call Cost Without CachingBiomeProvider

Every `PipelineBiomeProvider.getBiome(x, y, z, seed)` call pays:

1. **Domain warp** — two `mutator.getSample()` noise evaluations (not cached by anything)
2. **Caffeine chunk cache lookup** — 256-entry `biomeChunkCache`, cheap but not free
3. **Array index** — `compactBiomes[xInChunk * chunkSize + zInChunk]` — trivial

If the same block coordinate is queried multiple times within a chunk generation (which does happen
in `ChunkInterpolator`'s blend loop, slant calculation, and `NoiseChunkGenerator3D`'s block
placement loop), steps 1–2 are repeated in full each time.

---

## Why CachingBiomeProvider Is Not Enabled (and Probably Shouldn't Be)

### 1. Single-threaded executor bottleneck

`CACHE_EXECUTOR` is one thread shared globally. Under parallel chunk generation (many threads each
generating chunks simultaneously), all Caffeine async housekeeping tasks serialize on this executor.
The 981,504-slot 3D cache amplifies this — more slots means more async work per eviction cycle.

### 2. Cache size is excessive

981,504 slots × ~(16 bytes for key object + 4 bytes reference) ≈ **~19 MB per thread** in the
worst case, before accounting for `Biome` object references. This is allocated as `initialCapacity`
so the memory is reserved upfront. With e.g. 8 generation threads, this is ~152 MB of cache
infrastructure for data that the pipeline chunk cache already covers at a coarser granularity.

### 3. The expensive work is already cached

The expensive part of `getBiome()` is pipeline chunk generation (evaluating all pipeline stages).
That result is cached in a 256-entry Caffeine `biomeChunkCache` per `PipelineBiomeProvider`. Adding
block-level caching on top only saves the domain warp noise calls (2 evaluations) and the Caffeine
chunk lookup — both cheap relative to pipeline evaluation.

### 4. Miss behavior is bad in a high-miss regime

During initial chunk generation all lookups are cold misses. A cold miss on Caffeine with a
`CACHE_EXECUTOR` loader blocks the calling thread until the executor services it. Under load this
can cause generation threads to stall waiting for the single executor to drain.

---

## Alternatives

### Option A: Chunk-scoped block biome array (recommended)

Model it after `ChunkGenerationContext` — a `ThreadLocal<int[]>` (or `Biome[]`) reset at the start
of each chunk generation, indexed by chunk-local (x, z) coordinates.

```
blockOriginX, blockOriginZ                     // set at chunk start
array[lx * 16 + lz]                           // O(1) lookup, 256 slots per chunk
```

**Pros:**
- Zero allocation during chunk generation (array reused)
- No eviction, no executor, no contention
- Covers the 16×16 block footprint exactly
- Eliminates repeated domain warp calls for the same block

**Cons:**
- Only covers blocks within the current chunk — lookups from blend radius neighbors that extend
  outside the 16×16 footprint still miss
- Does not help with 3D (y-varying) queries; would need `lx * 16 * height + lz * height + ly`
  indexing for 3D coverage

### Option B: Per-column biome cache

Since CHIMERA uses `y-resolution: 8`, the same (x, z) column returns the same biome for a range
of y values. A column-keyed map `(lx, lz) → Biome` per chunk eliminates redundant domain warp
calls for the same column across multiple y levels.

```
if cachedColumn[lx][lz] != null: return it
else: evaluate, store, return
```

**Pros:**
- Eliminates most redundant `getBiome()` calls since block placement iterates y innermost
- Simple 16×16 array, no infrastructure

**Cons:**
- Assumes biome does not vary with y within a column (true for CHIMERA's pipeline which is 2D,
  but the extrusion layer adds 3D variation — need to be careful about call site)

### Option C: Do nothing

The domain warp cost (2 OpenSimplex2 evaluations per `getBiome()`) is small relative to the full
density evaluation chain. If profiling shows biome lookup is not a measurable fraction of chunk
generation time, the overhead is acceptable and adding caching complexity is not warranted.

---

## Recommendation

Profile first. If `getBiome()` appears in a meaningful percentage of chunk generation time, implement
**Option A** (chunk-scoped 16×16 2D array via `ThreadLocal`, reset per chunk). This is zero-cost
when biome lookups are not repeated, adds no executor risk, and fits the existing pattern established
by `ChunkGenerationContext`. Do not re-enable `CachingBiomeProvider` as-is.

---

## Key File References

| File | Relevance |
|---|---|
| `common/api/.../biome/generation/CachingBiomeProvider.java` | Implementation; CACHE_EXECUTOR usage |
| `common/api/.../biome/generation/BiomeProvider.java:95` | `.caching()` factory method |
| `common/implementation/base/.../config/pack/ConfigPackTemplate.java:110` | `cache.biome.enable` key, default `false` |
| `common/implementation/base/.../config/pack/ConfigPackImpl.java:229` | Conditional wrapping |
| `common/addons/biome-provider-pipeline/.../PipelineBiomeProvider.java:100` | Domain warp application; chunk cache |
| `common/addons/biome-provider-pipeline/.../pipeline/PipelineImpl.java:162` | `ChunkGenerationContext` lifecycle |
| `common/addons/biome-provider-pipeline/.../cache/ChunkScopedCacheSampler.java` | Sampler-level cache (not biome-level) |
