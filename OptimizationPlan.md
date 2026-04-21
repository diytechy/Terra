# Terra Engine Optimization Plan

## BiomeTool Benchmark Fidelity — Executive Summary

**BiomeTool IS a faithful benchmark of Terra's biome pipeline.** It exercises real Terra pipeline
code, real CHIMERA pack configs (copied from ORIGEN2 via `CopyPacks.bat`), and real Tectonic
infrastructure (35+ addon JARs resolved from `mavenLocal()`). Dummy implementations exist only for
Minecraft server abstractions (WorldHandle, BlockState, etc.) that are never invoked by the biome
pipeline itself.

**Caveats:**
- Single-threaded execution gives ~5–15% favorable skew vs. a real multithreaded server (no concurrent cache contention)
- Feature/structure generation is not exercised — but that is outside biome pipeline scope

**Conclusion:** BiomeTool results are reliable for relative comparisons, bottleneck identification,
and regression testing.

---

## Optimization Areas

Items are ordered by recommended attack priority. Each item includes a difficulty estimate and the
verification method via BiomeTool benchmark.

### Priority Legend
- **IMPACT**: HIGH / MEDIUM / LOW — expected performance gain
- **EFFORT**: S (small, hours) / M (medium, days) / L (large, multi-day)
- **CATEGORY**: A = Biome Pipeline Speed, B = Pack Load Speed, C = Chunk Generation Speed

---

## CATEGORY A — Biome Pipeline Speed (Highest Priority)

### A1 — Stream-Based Tag Matching in Hot Path ⭐
**IMPACT: HIGH | EFFORT: S | Category: A + C**

**Files:**
- [NoiseChunkGenerator3D.java:178](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/NoiseChunkGenerator3D.java#L178)
- [NoiseChunkGenerator3D.java:249](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/NoiseChunkGenerator3D.java#L249)

**Problem:**
Every Y level in the per-chunk block-placement inner loop calls `.getTags().stream().anyMatch()`.
This allocates a new Stream object and iterates tags on every block in the chunk (16×16×height,
typically 300+). With millions of chunks this is a massive allocation pressure source.

```java
// Inner loop — runs ~76,800 times per chunk
skipPackMinDensity = biomeMinDensitySampler == null
    && !minDensitySkipTags.isEmpty()
    && biome.getTags().stream().anyMatch(minDensitySkipTags::contains);  // ← ALLOCATION
```

**Fix:**
The `biome != lastMinDensityBiome` guard already caches per-biome — but the guard is not protecting
this specific flag correctly. Ensure `skipPackMinDensity` is computed **once** inside the
`if(biome != lastMinDensityBiome)` block, not on every iteration. Also replace the stream with a
plain loop:

```java
boolean skip = false;
for (String tag : biome.getTags()) {
    if (minDensitySkipTags.contains(tag)) { skip = true; break; }
}
skipPackMinDensity = biomeMinDensitySampler == null && skip;
```

**Verification:** BiomeTool benchmark — expect measurable T/s improvement, especially on packs with
`minDensitySkipTags` configured.

---

### A2 — Replace Stage: Sampler Called Even When Result Would Be "Self"
**IMPACT: MEDIUM | EFFORT: M | Category: A**

**Files:**
- [ReplaceStage.java:34–40](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/stage/mutators/ReplaceStage.java#L34)

**Problem:**
When a biome does match the `replaceableTag`, `replace.get(sampler, x, z, seed)` is called to get
the replacement biome. If the sampler maps to "self" (keep original biome), the sampler evaluation
was still paid for. Additionally, the sampler is evaluated even when it could be predicted to always
return the same result.

**Investigate:**
1. How often do replace stages resolve to "self" in CHIMERA? If high frequency, add a
   short-circuit pre-check.
2. Can sampler evaluation be deferred until after a fast biome-presence check?
3. Are any replace-stage samplers pure constants? If so, fold them at pack load time.

**Fix approach:** Add a cache at the `BiomeChunkImpl` level for sampler results at (x, z) so
repeated queries within the same chunk grid don't re-evaluate. Profile first to confirm real impact.

---

### A3 — BiomeChunkImpl: Two Full Arrays Allocated Per Chunk
**IMPACT: MEDIUM | EFFORT: M | Category: A**

**Files:**
- [BiomeChunkImpl.java:37–38](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/pipeline/BiomeChunkImpl.java#L37)

**Problem:**
Every chunk generates two full `PipelineBiome[]` arrays (`biomes` + `lookupArray`), sized to
include border padding. Only the center (chunkSize × chunkSize) area is returned. These are
discarded after use.

**Fix approach:** Implement `ThreadLocal<PipelineBiome[]>` pools for both arrays, reusing them
across chunk generations on the same thread. Requires clearing the array at the start of each use.

---

### A4 — ProbabilityCollection: Dead HashMap in Hot Path
**IMPACT: MEDIUM | EFFORT: S | Category: A**

**Files:**
- [ProbabilityCollection.java:29–32](common/api/src/main/java/com/dfsek/terra/api/util/collection/ProbabilityCollection.java#L29)

**Problem:**
`ProbabilityCollection` holds a `HashMap<E, MutableInteger>` that is populated during construction
but never consulted during the hot-path `get(Sampler, x, z, seed)` call. The map is dead weight
held in memory for every instance and adds to heap GC pressure.

**Fix:** Remove the `cont` HashMap entirely, or make it lazy (only instantiated if callers actually
need it). The array-based sampling path is complete without it.

---

## CATEGORY B — Pack Load Speed

### B1 — DeferredExpressionSampler: Synchronized Block Under Parallel Load
**IMPACT: MEDIUM | EFFORT: M | Category: B**

**Files:**
- [DeferredExpressionSampler.java:98–122](common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/config/sampler/DeferredExpressionSampler.java#L98)

**Problem:**
The double-checked locking pattern uses `synchronized(this)` for lazy expression compilation.
During pack loading with parallel threads, multiple threads compete for individual sampler locks,
degrading load throughput.

**Fix:**
Replace `synchronized` with `ReentrantReadWriteLock`. Compiled samplers are read far more than they
are written (compiled once, read many times). A read-write lock allows concurrent reads after first
compilation.

For Java 21+ (which is already in use): also evaluate `VarHandle.compareAndSet()` for a lockless
approach.

---

### B2 — PaletteHolder Builder: O(n) Scan Instead of TreeMap APIs
**IMPACT: LOW | EFFORT: S | Category: B**

**Files:**
- [PaletteHolder.java:55–82](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/palette/PaletteHolder.java#L55)

**Problem:**
Pack load uses `stream().min()` / `stream().max()` on a `TreeMap` (which already has O(1)
`firstKey()` / `lastKey()`), and the inner loop does a full O(n) scan per Y level instead of
using `ceilingEntry(y)`.

**Fix:** Use `paletteMap.firstKey()`, `paletteMap.lastKey()`, and `paletteMap.ceilingEntry(y)`.
Trivial change, clean win.

---

### B3 — PipelineImpl: Reflection-Based Sampler Unwrapping
**IMPACT: LOW | EFFORT: M | Category: B**

**Files:**
- [PipelineImpl.java:222–261](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/pipeline/PipelineImpl.java#L222)

**Problem:**
`unwrapDimensionApplicableSampler()` uses reflection (`getClass().getMethod("getSampler")`) to
unwrap sampler wrappers during pipeline initialization. Reflection defeats JIT inlining and is
slower than interface dispatch.

**Fix:** Add an `Unwrappable` marker interface (or a method on the relevant interface) so the cast
is direct. Eliminates reflection entirely.

---

## CATEGORY C — Chunk Generation Speed

### C1 — ElevationInterpolator: Fresh Array Allocation Per Chunk
**IMPACT: MEDIUM | EFFORT: M | Category: C**

**Files:**
- [ElevationInterpolator.java:18–54](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/math/interpolation/ElevationInterpolator.java#L18)

**Problem:**
A `BiomeNoiseProperties[26][26]` (approx.) array is freshly allocated for every chunk. Content is
populated from immutable biome properties and thrown away after use.

**Fix:** `ThreadLocal` array pool — allocate once per thread, reuse per chunk (same pattern as A3).

---

### C2 — SlantCalculationMethod: Vector Object Allocation Per Sample
**IMPACT: MEDIUM | EFFORT: M | Category: C**

**Files:**
- [SlantCalculationMethod.java:22–29](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/math/SlantCalculationMethod.java#L22)

**Problem:**
Every slant sample allocates a `Vector3.Mutable` for `normalApproximation` plus N more temporaries
via `point.mutable()` in the dot-product loop. With slant palettes active this runs millions of
times.

**Fix options (pick one):**
1. `ThreadLocal<Vector3.Mutable>` — reuse the accumulator across calls
2. Inline the math entirely — the DOT_PRODUCT_SAMPLE_POINTS array is fixed (6 points), so unroll
   the loop and accumulate with plain `double` variables. No objects needed.

Option 2 is preferred: zero allocation, trivially inlinable by JIT.

---

### C5 — LazilyEvaluatedInterpolator: Biome Lookup Chain on Every Cache Miss
**IMPACT: MEDIUM | EFFORT: S | Category: C**

**Files:**
- [LazilyEvaluatedInterpolator.java:47–112](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/math/interpolation/LazilyEvaluatedInterpolator.java#L47)

**Problem:**
On cache miss, the carving sampler is fetched via a full chain:
`.getBiome().getContext().get(noisePropertiesKey).samplers().carving()`. If the same biome is at
multiple coordinates, the lookup chain is repeated unnecessarily.

**Fix:** Add a `Biome → CarvingSampler` map (populated lazily) within the interpolator's scope for
the current chunk. Cost: one map lookup per cache miss vs. a 4-call chain.

---

### C4 — ChunkInterpolator: Unnecessary Intermediate Array Copy
**IMPACT: LOW | EFFORT: S | Category: C**

**Files:**
- [ChunkInterpolator.java:69–101](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/math/interpolation/ChunkInterpolator.java#L69)

**Problem:**
`centerColumns[25]` is filled then copied into the larger `columns` array in a second pass.

**Fix:** Populate `columns` directly during the first loop, eliminating the intermediate array.

---

### C6 — ThreadLocalNoiseHolder: Single-Slot Cache
**IMPACT: LOW | EFFORT: M | Category: C**

**Files:**
- [ThreadLocalNoiseHolder.java:9–24](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator\config\noise\ThreadLocalNoiseHolder.java#L9)

**Problem:**
The per-sampler per-thread cache holds exactly one (x, y, z, seed) → noise value. Any two
consecutive distinct-coordinate calls evict the cache. Actual hit rate is likely very low.

**Investigate:** Profile hit rate before investing in an expansion. If hit rate is < 5%, consider
removing the cache entirely (saves the comparison overhead). If > 20%, expand to 4–8 slots.

---

## Java 25 Opportunities

| Opportunity | Where | Benefit |
|---|---|---|
| **Virtual threads** | DeferredExpressionSampler (B1), parallel pack load | Better lock throughput; no carrier thread pinning |
| **Record patterns** | BiomeNoiseProperties unpacking in NoiseChunkGenerator3D | Cleaner destructuring, no boxing |
| **Vector API (SIMD)** | Trilinear interpolation in ChunkInterpolator | 2–4× speedup on lerp math (incubator, test carefully) |
| **Primitive value types** (preview) | Vector3, Interpolator | Eliminate heap allocation for small math objects |
| **Sequenced collections** | TreeMap accesses in PaletteHolder (B2) | Cleaner API (`getFirst()`, `getLast()`) |

Note: Java 25 is a standard release (not LTS). Ensure target JVM is locked to 25 before adopting
preview features. Vector API is still incubating — benchmark against scalar code before committing.

---

## Attack Order (Recommended)

| # | Item | Category | Impact | Effort | Notes |
|---|------|----------|--------|--------|-------|
| 1 | A1 — Fix stream tag matching | A + C | HIGH | S | Guaranteed win; no risk |
| 2 | A4 — Remove dead HashMap | A | MEDIUM | S | Trivial cleanup |
| 3 | B2 — TreeMap API fix | B | LOW | S | 15-min fix |
| 4 | C2 — Inline slant math | C | MEDIUM | M | Zero-allocation win if slant enabled |
| 5 | C5 — Cache carving sampler | C | MEDIUM | S | Small scope, clear benefit |
| 6 | C4 — Remove column copy | C | LOW | S | Trivial |
| 7 | A3 + C1 — ThreadLocal array pools | A + C | MEDIUM | M | Same pattern, do together |
| 8 | A2 — Replace-stage short-circuit | A | MEDIUM | M | Profile first |
| 9 | B1 — RWLock for expression compile | B | MEDIUM | M | Pack load only |
| 10 | B3 — Remove reflection in pipeline init | B | LOW | M | Clean up, low urgency |
| 11 | C6 — Profile + tune noise cache | C | LOW | M | Measure before acting |
| 12 | Java 25 SIMD / value types | A + C | HIGH (potential) | L | Validate JVM version first |

---

## Rebuild & Benchmark Workflow

See [RebuildDepsAndBenchmark.bat](C:\Projects\BiomeTool\RebuildDepsAndBenchmark.bat) for the
automated dependency-chain rebuild and benchmark runner.

Manual steps if selective rebuild is needed:

```
1. (If Tectonic changed)   cd C:\Projects\Tectonic   && gradlew.bat publishToMavenLocal
2. (If Terra changed)      cd C:\Projects\Terra       && gradlew.bat publishToMavenLocal
3. (If BiomeTool changed or Terra/Tectonic republished)
                           cd C:\Projects\BiomeTool   && gradlew.bat build
4.                         C:\Projects\BiomeTool\RunBenchmark.bat
5. Read: C:\Projects\BiomeTool\benchmark_*.csv
```

ORIGEN2/Chimera changes do NOT require `publishToMavenLocal` — `CopyPacks.bat` (called by
`RunBenchmark.bat`) handles the pack deployment automatically.
