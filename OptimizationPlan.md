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

### ~~A2 — Replace Stage: Sampler Called Even When Result Would Be "Self"~~ (INVALIDATED)
~~**IMPACT: MEDIUM | EFFORT: M | Category: A**~~

**Finding after code review:** No viable optimization path exists:
1. `ProbabilityCollection.get()` must call the sampler to index into its backing array — SELF vs real biome
   cannot be determined without the sampler output.
2. `ProbabilityCollection.Singleton` already short-circuits the sampler for single-entry collections.
3. The tag check is already the main guard — sampler is only called when the tag matches.
4. Each grid point is processed exactly once per stage — no repeated evaluations to cache.

CHIMERA has 117 SELF entries in replace stages (e.g., SELF:5 vs real biome:1 ≈ 83% SELF), but the
sampler must still be called to determine which bucket a given (x,z) falls into. **No action required.**

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

### ~~A4 — ProbabilityCollection: Dead HashMap in Hot Path~~ (INVALIDATED)
~~**IMPACT: MEDIUM | EFFORT: S | Category: A**~~

**Finding after code review:** `cont` is NOT dead weight. It is actively used by six Collection
interface methods (`contains`, `iterator`, `toArray`, `containsAll`, `getContents`, `toString`) and
`getProbability()`. Removing it would break the public API. The hot-path `get(Sampler, ...)` methods
don't use it, but it serves a legitimate purpose as the backing store for distinct-item tracking and
probability counts. **No action required.**

---

## CATEGORY B — Pack Load Speed

### ~~B1 — DeferredExpressionSampler: Synchronized Block Under Parallel Load~~ (INVALIDATED)
~~**IMPACT: MEDIUM | EFFORT: M | Category: B**~~

**Finding after code review:** The current implementation is already optimal. The `volatile` +
double-checked locking pattern means the `synchronized` block is **never entered** after the first
compilation. Post-compilation reads take the fast path (`volatile` read + null check) with zero lock
overhead — concurrent reads already work without any lock. A `ReentrantReadWriteLock` would be
slower for the dominant read case, as read-lock acquire/release costs more than a single `volatile`
read. **No action required.**

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

### ~~C4 — ChunkInterpolator: Unnecessary Intermediate Array Copy~~ (INVALIDATED)
~~**IMPACT: LOW | EFFORT: S | Category: C**~~

**Finding after code review:** The two-loop structure is intentional ("Option 5" comment). `columns` can only
be allocated after `localMaxBlend` is known, which requires the first loop to complete. Eliminating
`centerColumns` would require either re-fetching 25 columns a second time or allocating `columns` at
the global `maxBlend` size upfront, both of which are regressions. The 25-element copy loop is
negligible. **No action required.**

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

Note: Java 25 is an LTS release (the successor to Java 21 LTS, following the 11 → 17 → 21 → 25
cadence). It is a stable long-term target. Vector API is still incubating — benchmark against
scalar code before committing.

---

## Attack Order (Recommended)

| # | Item | Category | Impact | Effort | Notes |
|---|------|----------|--------|--------|-------|
| 1 | A1 — Fix stream tag matching | A + C | HIGH | S | Guaranteed win; no risk |
| 2 | ~~A4 — Remove dead HashMap~~ | — | — | — | INVALIDATED: cont is actively used |
| 3 | B2 — TreeMap API fix | B | LOW | S | 15-min fix |
| 4 | C2 — Inline slant math | C | MEDIUM | M | Zero-allocation win if slant enabled |
| 5 | C5 — Cache carving sampler | C | MEDIUM | S | Small scope, clear benefit |
| 6 | C4 — Remove column copy | C | LOW | S | Trivial |
| 7 | A3 + C1 — ThreadLocal array pools | A + C | MEDIUM | M | Same pattern, do together |
| 8 | ~~A2 — Replace-stage short-circuit~~ | — | — | — | INVALIDATED: sampler must be called to determine SELF vs real biome |
| 9 | ~~B1 — RWLock for expression compile~~ | — | — | — | INVALIDATED: volatile DCL already gives zero-overhead concurrent reads |
| 10 | B3 — Remove reflection in pipeline init | B | LOW | M | Clean up, low urgency |
| 11 | C6 — Remove ThreadLocalNoiseHolder | C | LOW | M | Removed — always-miss overhead on non-blend path |
| 12 | Java 25: Inline Interpolator3 + triLerp | C | MEDIUM | S | DONE — eliminates 2 allocs/cell; FMA already in Seismic |
| 12b | Java 25: Vector API (SIMD) for triLerp | C | HIGH (potential) | L | Future — needs `--add-modules jdk.incubator.vector`, Gradle config, benchmarking |

---

## Rebuild & Benchmark Workflow

See [RebuildDepsAndBenchmark.bat](C:\Projects\BiomeTool\RebuildDepsAndBenchmark.bat) for the
automated dependency-chain rebuild and benchmark runner.

Manual steps if selective rebuild is needed:

```
1. (If Tectonic changed)
      cd C:\Projects\Tectonic && gradlew.bat publishToMavenLocal

2. (If Terra changed — Terra is versioned by its git short hash)
   a. cd C:\Projects\Terra && gradlew.bat build          (verify compile, no publish)
   b. git add -A && git commit -m "your message"         (hash changes after this)
   c. gradlew.bat publishToMavenLocal                    (publishes under new hash)
   d. In C:\Projects\BiomeTool\build.gradle.kts, update:
        val terraGitHash = "<new short hash from git rev-parse --short HEAD>"

3. (If BiomeTool changed or Terra/Tectonic republished)
      cd C:\Projects\BiomeTool && gradlew.bat build

4.    C:\Projects\BiomeTool\RunBenchmark.bat

5. Read: C:\Projects\BiomeTool\benchmark_*.csv
```

ORIGEN2/Chimera changes do NOT require `publishToMavenLocal` — `CopyPacks.bat` (called by
`RunBenchmark.bat`) handles the pack deployment automatically.
