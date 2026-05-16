# Plan: Automatic Chunk-Scoped Biome Pipeline Sampler Cache

## Context

Currently, caching intermediate sampler results in the biome pipeline requires explicit `CACHE` sampler declarations in pack configs. These use direct-mapped thread-local arrays that store per-coordinate values indefinitely (never freed), growing unboundedly over time. This plan replaces that manual approach with an automatic system that:

- Caches named pack-level sampler results within the scope of a single biome chunk generation
- Determines which samplers to cache based on estimated complexity × usage count
- Uses a bounded, reusable thread-local cache (freed when the thread dies, reset between chunks)
- Eliminates the need for explicit `CACHE` sampler declarations in the biome pipeline

---

## Architecture: How It Works

When `PipelineBiomeProvider` triggers a chunk miss in its BiomeChunk Caffeine cache, `PipelineImpl.generateChunk()` is called. All sampler evaluations for that chunk happen synchronously on the calling thread. The new system:

1. Sets a thread-local `ChunkGenerationContext` (holding a flat `double[]` per cached sampler)
2. Wraps selected pack sampler `LastValueSampler` delegates with `ChunkScopedCacheSampler`
3. During chunk generation: `ChunkScopedCacheSampler` checks the context, serves/stores results indexed by chunk-local position
4. After generation: context origin is invalidated (so no stale hits between chunks)

### Coordinate Indexing

`viewPoint.worldX()` returns `(worldOrigin.x + xIndex - chunkOriginArrayIndex) * resolution` (block coordinates, int-valued doubles).

Cache array index: `lx = (int)(worldX - blockOriginX) / resolution`
where `blockOriginX = (worldOrigin.x - chunkOriginArrayIndex) * resolution`.

This maps xIndex directly to [0, arraySize), giving O(1) lookup with no hashing.

---

## Files to Create

### 1. `ChunkGenerationContext.java`
**Location:** `common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/`

Holds the per-thread chunk context:
- `int blockOriginX, blockOriginZ` — block-coordinate of array index 0
- `int resolution, arraySize`
- `double[][] caches` — one `double[arraySize * arraySize]` per registered cached sampler slot
- `reset(blockOriginX, blockOriginZ, resolution)` — does `Arrays.fill(cache, NaN)` for each active slot
- `getIndex(worldX, worldZ)` — `((int)(worldX - blockOriginX) / resolution) * arraySize + (int)(worldZ - blockOriginZ) / resolution`
- `inBounds(worldX, worldZ)` — bounds check

### 2. `ChunkScopedCacheSampler.java`
**Location:** `common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/`

`Sampler` wrapper:
- Holds `ThreadLocal<ChunkGenerationContext> contextRef` and `int slot`
- `getSample(seed, x, y)` (2D): check `contextRef.get()` — if null or `!inBounds`, delegate directly. Otherwise check `cache[slot][idx]` for NaN; if NaN, evaluate + store; return cached value.
- `getSample(seed, x, y, z)` (3D): always delegates (pipeline is 2D only)

### 3. `SamplerComplexityEstimator.java`
**Location:** `common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/`

Static utility with `int estimate(Sampler s)` using `instanceof` dispatch + `IdentityHashMap` memoization:

| Sampler type | Score |
|---|---|
| `ConstantNoiseSampler`, `WhiteNoiseSampler`, `PositiveWhiteNoiseSampler` | 1 |
| `OpenSimplex2Sampler`, `PerlinSampler`, `SimplexSampler`, `ValueSampler`, `ValueCubicSampler` | 8 |
| `GaussianNoiseSampler` | 10 |
| `CellularSampler` | 25 |
| `GaborNoiseSampler` | 40 |
| `PseudoErosionSampler` | 80 |
| Fractal (`BrownianMotionSampler`, `PingPongSampler`, `RidgedFractalSampler`) | `octaves × child_score` (default octaves=6 if not accessible) |
| `DomainWarpSampler` | `2 × child_score + warp_score` |
| Arithmetic (`AddSampler`, `SubtractionSampler`, `MulSampler`, `DivSampler`, `MaxSampler`, `MinSampler`) | `left + right + 1` |
| `DeferredExpressionSampler` / `ExpressionNoiseFunction` | 50 (conservative; can't introspect AST) |
| `LastValueSampler` | recurse on `getDelegate()` |
| `CacheSampler` | 1 (already cached) |
| Unknown | 15 (safe default) |

### 4. `PipelineSamplerAnalysis.java`
**Location:** `common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/`

Run at `PipelineImpl` construction. Takes `(Source source, List<Stage> stages, Map<String, DimensionApplicableSampler> packSamplers, int arraySize)`.

Steps:
1. Walk source and stages via `SamplerReferenceWalker` — for each sampler field, check identity against packSamplers' `LastValueSampler` instances; record use count per sampler name.
2. For expressions: conservatively increment all pack sampler counts +1 (can't introspect AST).
3. For each named 2D pack sampler with use count > 0: compute `weight = complexity × useCount`.
4. Sort by weight descending. Compute max slots from memory budget: `(250_000 bytes per thread) / (arraySize² × 8)` capped at the number of eligible samplers.
5. Return `List<SelectedSampler>` with (name, DimensionApplicableSampler, slot) assignments.

### 5. `SamplerReferenceWalker.java`
**Location:** `common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/`

Walks the pipeline source + stage sampler fields to count pack sampler identity matches:
- `SamplerSource.getSampler()` — need accessor added (see modifications below)
- Each stage via `stage.getSampler()` — need accessor added to `Stage` interface (default `null`)
- Checks: if the sampler IS a `LastValueSampler` → `getDelegate()` → compare to known pack sampler inner instances

---

## Files to Modify

### `LastValueSampler.java`
**Change:** Remove `final` from `private final Sampler delegate`. Add:
```java
// Package-private; used by PipelineSamplerAnalysis to install chunk-scope cache
void setDelegate(Sampler newDelegate) {
    this.delegate = newDelegate;
}
```
**Why:** The `NoiseFunction2` in compiled expressions holds the `LastValueSampler` directly (captured at expression compile time). To intercept those calls, we must modify the delegate in-place rather than wrapping the `LastValueSampler`.

### `Stage.java` (interface)
Add default method:
```java
default @Nullable Sampler getSampler() { return null; }
```
This allows `SamplerReferenceWalker` to access stage samplers without `instanceof` casts.

### `SamplerSource.java`
Add accessor:
```java
public Sampler getSampler() { return sampler; }
```

### `ReplaceStage.java`, `BorderStage.java`, `ReplaceListStage.java`, `BorderListStage.java`, `SmoothStage.java`
Override `getSampler()` to expose the stage's internal sampler field.

### `PipelineImpl.java`
1. Add `private final ThreadLocal<ChunkGenerationContext> chunkContextLocal = new ThreadLocal<>()`
2. Add `private final int numCachedSamplers` field
3. In constructor: after computing `arraySize`, call `PipelineSamplerAnalysis.analyze(...)`, install `ChunkScopedCacheSampler` wrappers via `lastValueSampler.setDelegate(new ChunkScopedCacheSampler(originalDelegate, chunkContextLocal, slot))`, store `numCachedSamplers`.
4. Modify `generateChunk()`:

```java
@Override
public BiomeChunk generateChunk(SeededVector2Key worldCoordinates) {
    if (numCachedSamplers == 0) return new BiomeChunkImpl(worldCoordinates, this);
    ChunkGenerationContext ctx = chunkContextLocal.get();
    if (ctx == null) {
        ctx = new ChunkGenerationContext(numCachedSamplers, arraySize);
        chunkContextLocal.set(ctx);
    }
    int blockOriginX = (worldCoordinates.x - chunkOriginArrayIndex) * resolution;
    int blockOriginZ = (worldCoordinates.z - chunkOriginArrayIndex) * resolution;
    ctx.reset(blockOriginX, blockOriginZ, resolution);
    try {
        return new BiomeChunkImpl(worldCoordinates, this);
    } finally {
        ctx.invalidate(); // sets blockOriginX to MIN_VALUE so inBounds always false
    }
}
```

### `BiomePipelineTemplate.java`
Add `PackSamplerContext` to constructor and pass `packSamplers` map to `PipelineImpl`.

```java
public BiomePipelineTemplate(Profiler profiler, PackSamplerContext packSamplerContext) { ... }
```

Pass `packSamplerContext.getPackSamplers()` as a new parameter to `PipelineImpl()`.

### `BiomePipelineAddon.java`
Update the `BiomePipelineTemplate` registration to supply `PackSamplerContext` from the pack context (`event.getPack().getContext().get(PackSamplerContext.class)`).

---

## Dependency / Initialization Order

Pack loading sequence (verified):
1. `NoiseAddon` initializes → pack samplers loaded, wrapped in `LastValueSampler`, stored in `PackSamplerContext`
2. Expression samplers compile (deferred compilation disabled) → `NoiseFunction2` objects capture `LastValueSampler` instances
3. **Biome configs load** → `BiomePipelineTemplate.get()` is called → `PipelineImpl` constructor runs → analysis + delegate swap happens HERE
4. World generation begins → `generateChunk()` uses context + `ChunkScopedCacheSampler`

The delegate swap at step 3 is AFTER expression compilation (step 2), but since `NoiseFunction2` holds the `LastValueSampler`, and `LastValueSampler` now routes through the new `ChunkScopedCacheSampler` delegate, all call paths are intercepted correctly.

---

## Memory Budget

Per thread per chunk generation: `numSlots × arraySize² × 8` bytes
With arraySize=64, 4 slots: `4 × 4096 × 8 = 131 KB/thread`
8 threads: ~1 MB total (vs. current unbounded `CacheSampler` arrays)

Max slots formula: `Math.min(eligibleSamplers.size(), (int)(BUDGET_BYTES_PER_THREAD / (arraySize * arraySize * 8L)))`
where `BUDGET_BYTES_PER_THREAD = 262_144` (256 KB, configurable constant).

---

## Risk: Multiple Pipelines per Pack

If a pack defines two `PIPELINE` biome providers, both call `PipelineImpl` constructor and both would try to swap `LastValueSampler` delegates. The second pipeline would overwrite the first's `ChunkScopedCacheSampler` (and the first pipeline's thread-local context would no longer be referenced by the sampler).

**Mitigation (Phase 2):** Before swapping a delegate, check if it's already a `ChunkScopedCacheSampler`. If so, wrap it in a `MultiContextCacheSampler` that checks both contexts. For Phase 1, log a warning if a delegate is already a `ChunkScopedCacheSampler`.

---

## Deprecation

`CACHE` sampler type: do NOT remove. It still serves use cases outside the biome pipeline (e.g., terrain generation samplers). Add a deprecation note/warning to `CacheSamplerTemplate` pointing users toward the new automatic behavior for pipeline contexts.

---

## Implementation Phases

**Phase 1: Infrastructure**
- `ChunkGenerationContext` data class
- `SamplerComplexityEstimator` with tests
- Add `setDelegate()` to `LastValueSampler`
- Add `getSampler()` to `Stage` interface and implementing classes + `SamplerSource`

**Phase 2: Cache Wrapper**
- `ChunkScopedCacheSampler` with unit tests (no-context passthrough, context cache hit/miss, bounds check)

**Phase 3: Analysis**
- `SamplerReferenceWalker`
- `PipelineSamplerAnalysis`

**Phase 4: Integration**
- Modify `PipelineImpl` (constructor analysis + context push/pop in `generateChunk()`)
- Modify `BiomePipelineTemplate` + `BiomePipelineAddon` to pass `PackSamplerContext`

**Phase 5: Validation**
- Add `INFO` log in `PipelineSamplerAnalysis` showing selected samplers, weights, memory footprint
- Profile with ORIGEN2 biome pipeline to verify cache hit rate and generation speedup
- Tune complexity tier constants if needed

---

## Critical Files Reference

| File | Role | Action |
|---|---|---|
| `PipelineImpl.java` | Core pipeline; `generateChunk()` | Add context thread-local, analysis call, context push/pop |
| `BiomePipelineTemplate.java` | Pipeline construction from YAML | Accept + pass `PackSamplerContext` |
| `BiomePipelineAddon.java` | Template registration | Supply `PackSamplerContext` |
| `LastValueSampler.java` | Delegate swap pivot point | Remove `final` on delegate, add `setDelegate()` |
| `BiomeChunkImpl.java` | Coordinate math reference | Read-only; defines `xIndexToWorldCoordinate()` formula |
| `Stage.java` | Stage interface | Add default `getSampler()` method |
| `SamplerSource.java`, stage mutators | Sampler field exposure | Add `getSampler()` overrides |
| `PackSamplerContext.java` | Pack sampler map | Pass to `PipelineImpl`; expose `getPackSamplers()` |
