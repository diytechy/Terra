# Terra / Tectonic Memory Optimization Plan

**Created:** 2026-04-11  
**Context:** Investigating OOM crash on server startup when loading CHIMERA (ORIGEN2) pack alongside TARTARUS, REIMAGEND, and OVERWORLD packs. Windows paging file exhausted during G1GC heap region commitment (~750 MB batch request).

---

## Background: What Caused the OOM

The server crashed with:
```
os::commit_memory(0x00000006ccc00000, 750780416, 0) failed;
error='The paging file is too small for this operation to complete' (DOS error/errno=1455)
# Native memory allocation (mmap) failed to map 750780416 bytes. Error detail: G1 virtual space
```

This is **not** 750 MB of Java objects — it is G1GC committing a batch of heap regions from the OS. The JVM had reserved 8 GB (`-Xmx8G`) but Windows couldn't back the next committed batch because physical RAM + pagefile was exhausted. The trigger was loading CHIMERA (ORIGEN2), the largest pack, after three other packs had already consumed most of the committed heap.

### Pack Size Comparison (at time of investigation)

| Pack | yml files | Disk size |
|---|---|---|
| TerraOverworldConfig | 872 | 18 MB |
| ORIGEN2 (CHIMERA) | 1,653 | 63 MB |

ORIGEN2 was ~3.5× larger on disk, with 2× the biomes, 2.3× the palettes, and 2.5× the biome-distribution files.

---

## Phase 0: Pack Cleanup (Completed)

Before optimizing Terra/Tectonic internals, dead config files were removed from ORIGEN2 to reduce object count at the source.

### Files Deleted from ORIGEN2

**Dead concrete biomes (never placed in any pipeline):**
- `biomes/biomes/lavender_crags.yml`
- `biomes/biomes/woodlands/sakura_woodlands.yml`
- `biomes/biomes/water/snowdrift_coasts.yml` + `biomes/equations/biome_specific/eq_snowdrift_coasts.yml`

**Debug-only biomes (rearth/debug/, only in colors.generated.yml):**
- `biomes/rearth/debug/coast_cold_a.yml`
- `biomes/rearth/debug/coast_cold_b.yml`
- `biomes/rearth/debug/coast_medium_b.yml`
- `biomes/rearth/debug/coast_warm_a.yml`
- `biomes/rearth/debug/coast_warm_b.yml`
- `biomes/rearth/debug/land_cold_a.yml`
- `biomes/rearth/debug/land_cold_b.yml`

**Dead abstract biomes (no extends references anywhere):**
- `biomes/abstract/carving/carving_substratum.yml`
- `biomes/abstract/environment/land/dry/environment_land_dry_orange_desert.yml`
- `biomes/abstract/environment/land/unique/environment_land_unique_volcanic.yml`
- `biomes/equations/biome_specific/eq_boreal_peaks.yml`
- `biomes/abstract/terrain/land/eq_columns.yml`
- `biomes/abstract/terrain/land/eq_craters.yml`
- `biomes/abstract/terrain/land/legacy/eq_plain_peaks.yml`
- `biomes/abstract/terrain/land/legacy/eq_sea_caves.yml`
- `biomes/abstract/terrain/spot/eq_spot_mesa.yml`
- `biomes/abstract/terrain/land/eq_terraced_land.yml`
- `biomes/abstract/placeholders/TEST_PLACEHOLDER_ISLAND.yml`

**Dead features (32 files):** See git history for full list — geological slabs, unused vegetation, dead trees, substratum utilities, rearth structures never referenced by any biome.

**Dead palettes (18 files):** Includes `snowy_tundra`, `sculk_grass`, `sculk_swamp`, `moss_andesite`, `moss_deepslate`, `lava_cracks_*`, `isles_ice`, `grass_isles`, `snowy_*_isles`, and others with zero biome references.

**Total deleted:** ~72 yml files. ORIGEN2 reduced from 1,653 → ~1,581 yml files.

### Pipeline Reachability Analysis

A full forward-reachability analysis was run starting from `biome-distribution/presets/CHIMERA.yml`, expanding transitively through all pipeline stage files and `extends` inheritance chains. Result:

- 692 biome yml files total
- 679 reachable (551 direct pipeline refs + 128 via extends closure)
- **13 unreachable** — presented to user for review before deletion (some may be planned-but-unwired content)

**Remaining unreachable biomes (do not auto-delete — verify intent first):**
- `abstract/features/ores/ores_coal.yml` — ore mixin never applied
- `abstract/placeholders/_cold_canyon.yml`, `_cold_sinkhole.yml`, `_mod_sinkhole.yml`, `_warm_canyon.yml`, `_warm_sinkhole.yml` — 5 canyon/sinkhole placeholder stubs
- `abstract/terrain/land/legacy/eq_flat.yml` — legacy terrain equation
- `cave/lush_caves.yml`, `cave/substratum/coral_coves.yml` — recently re-added to distribution; may be stale in analysis
- `land/maritime/wet/gallery_forest.yml`, `orange_gallery_forest.yml`, `red_gallery_forest.yml` — confirmed dead
- `substratum_foundation/caves.yml` — top-level CAVES abstract

---

## Phase 1: What Lives in Heap Per Biome (The Real Problem)

After pack load completes, the raw YAML `Map<String, Configuration>` is discarded (it's a local variable in `ConfigPackImpl`). However, for each biome the following object graph is **permanently retained** via the `Context` property bag on `UserDefinedBiome`:

| Property | Class | Key contents |
|---|---|---|
| Noise samplers | `BiomeNoiseProperties` | 3 `Sampler` trees (base, elevation, carving), blend params, `ThreadLocalNoiseHolder` |
| Palette stack | `BiomePaletteInfo` | `PaletteHolder` (Palette[] ~384 entries), `SlantHolder`, ocean Palette, sea level sampler |
| Feature list | `BiomeFeatures` | `Map<FeatureGenerationStage, List<Feature>>` |
| Structure weights | `BiomeStructures` | structure weight map |
| Tag query data | `BiomeTagHolder` | flattened tag set |
| Vanilla biome props | `VanillaBiomeProperties` | sound/particle/mood config |

Individual `Palette` objects **are** shared (they live in the pack registry). But each biome gets its own:
- `Palette[]` array in `PaletteHolder` (even if two biomes have identical palette stacks)
- Full `Sampler` object tree (even if 600 biomes all inherit the same `terrain.sampler` from `BASE`)
- `ThreadLocalNoiseHolder` (legitimately unique — caches per-thread noise results)

### Root cause of duplication

In `AbstractConfigLoader.loadConfigs()` → `ConfigLoader.load()`:

1. When a child biome inherits `terrain.sampler` from parent `BASE`, Tectonic's `AbstractConfiguration.get("terrain.sampler")` returns the **same raw Java object** (the `Map` from SnakeYAML) that BASE's `Prototype.getConfig()` holds.
2. `ConfigLoader.loadType()` is then called on this raw object — and **always constructs a new instance** with no caching.
3. Result: 600 biomes that all inherit `terrain.sampler` from BASE get 600 structurally identical but separately allocated Sampler trees.

---

## Phase 1 Implementation: Two Parallel Changes

### 1A — Terra: Split `ThreadLocalNoiseHolder` out of `BiomeNoiseProperties`

**File:** `common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/config/noise/BiomeNoiseProperties.java`

**Motivation:** `BiomeNoiseProperties` is a record bundling sharable sampler config with a non-sharable `ThreadLocalNoiseHolder`. This prevents safe caching of the record. Separating them allows the sampler config to become the cacheable unit.

**Current:**
```java
public record BiomeNoiseProperties(
    Sampler base,
    Sampler elevation,
    Sampler carving,
    int blendDistance,
    int blendStep,
    double blendWeight,
    double elevationWeight,
    ThreadLocalNoiseHolder noiseHolder
) implements Properties {}
```

**After:**
```java
// New: the immutable, cacheable, shareable part
public record BiomeNoiseSamplers(
    Sampler base,
    Sampler elevation,
    Sampler carving,
    int blendDistance,
    int blendStep,
    double blendWeight,
    double elevationWeight
) {}

// Updated: wraps the shared config + per-biome mutable holder
public record BiomeNoiseProperties(
    BiomeNoiseSamplers samplers,
    ThreadLocalNoiseHolder noiseHolder
) implements Properties {}
```

**Callers to update:**
- `BiomeNoiseConfigTemplate.get()` — construct `BiomeNoiseSamplers`, wrap in `BiomeNoiseProperties`
- `NoiseChunkGenerator3DAddon` — update all field access (`.base()` → `.samplers().base()`, etc.)
- `Sampler3D`, `ChunkInterpolator`, `ElevationInterpolator`, `LazilyEvaluatedInterpolator` — update field access

After this change, when the Tectonic cache (1B) deduplicates `BiomeNoiseSamplers` instances, the `ThreadLocalNoiseHolder` (constructed fresh in `BiomeNoiseProperties`) remains unique per biome.

---

### 1B — Tectonic: Session-scoped type-load cache in `ConfigLoader`

**File:** `common/src/main/java/com/dfsek/tectonic/api/loader/ConfigLoader.java`

**Motivation:** Raw YAML objects from parent `Prototype` configs are the same Java instance for every child that inherits a key. By caching type-load results keyed on the raw object identity + target type, we avoid re-constructing the same Sampler/Palette/etc. trees for every biome.

**Mechanism:** Add an `IdentityHashMap`-based session cache. The cache must be scoped to a single `AbstractConfigLoader.loadConfigs()` call — not shared across packs or reused across server reloads.

**Implementation in `ConfigLoader`:**

```java
// Session cache: raw object identity → (target Type → loaded result)
// Null when not in a session (direct ConfigLoader.load() calls)
private IdentityHashMap<Object, Map<Type, Object>> sessionCache = null;

/** Enable session caching for the duration of a pack load. */
public void beginSession() {
    this.sessionCache = new IdentityHashMap<>();
}

/** Clear session cache after pack load completes. */
public void endSession() {
    this.sessionCache = null;
}
```

In `getObject()`, wrap the existing construction with a cache lookup:

```java
private Object getObject(AnnotatedType t, Object o, DepthTracker depthTracker) throws LoadException {
    // Only cache non-primitive, non-String objects from parent configs
    // (primitives are immutable and boxing handles them; Strings are already interned)
    if (sessionCache != null && o instanceof Map || o instanceof List) {
        Type raw = extractRawType(t);
        Map<Type, Object> byType = sessionCache.computeIfAbsent(o, k -> new HashMap<>());
        Object cached = byType.get(raw);
        if (cached != null) return cached;
        Object built = getObjectInternal(t, o, depthTracker);
        byType.put(raw, built);
        return built;
    }
    return getObjectInternal(t, o, depthTracker);
}
```

Rename existing `getObject` body to `getObjectInternal`.

**Wire into `AbstractConfigLoader`:**

```java
public Set<AbstractConfiguration> loadConfigs(List<Configuration> configurations) throws ConfigException {
    delegate.beginSession();
    try {
        // ... existing logic unchanged ...
    } finally {
        delegate.endSession();
    }
}
```

**Safety constraints:**
- Only cache when `o` is a `Map` or `List` (raw YAML structures from SnakeYAML). Primitives, Strings, and already-typed objects are excluded.
- Do not cache objects whose construction has side effects (e.g., random seed initialization). In Terra's case, `ThreadLocalNoiseHolder` is constructed in `BiomeNoiseProperties`'s record canonical constructor, not via `loadType`, so it is not affected.
- The cache is discarded (`endSession()`) immediately after `loadConfigs` returns, so it never leaks across packs or worlds.

---

### 1C — Terra (bonus, no Tectonic needed): PaletteHolder interning

**File:** `NoiseChunkGenerator3DAddon.java` in the `ConfigPackPostLoadEvent` handler

After all biomes are loaded, walk every biome's `BiomePaletteInfo` and replace duplicate `PaletteHolder` instances with a single canonical instance using content-equality comparison. This requires making `PaletteHolder.palettes` accessible (package-private or adding an equality method).

```java
// In ConfigPackPostLoadEvent handler:
Map<List<Palette>, PaletteHolder> holderIntern = new HashMap<>();
pack.getRegistry(Biome.class).forEach((key, biome) -> {
    BiomePaletteInfo info = biome.getContext().get(paletteInfoPropertyKey);
    List<Palette> key = Arrays.asList(info.paletteHolder().getPalettes());
    PaletteHolder canonical = holderIntern.computeIfAbsent(key, k -> info.paletteHolder());
    if (canonical != info.paletteHolder()) {
        // replace context entry with canonical-holdered BiomePaletteInfo
        biome.getContext().replace(paletteInfoPropertyKey,
            new BiomePaletteInfo(canonical, info.slantHolder(), info.ocean(),
                info.seaLevel(), info.seaLevelSampler(), info.updatePaletteWhenCarving()));
    }
});
```

Requires adding `getPalettes()` accessor to `PaletteHolder` and a `replace` method to `Context` (or re-`put` after clearing).

---

### 1D — Terra (minor): Context dual-storage elimination

**File:** `common/api/src/main/java/com/dfsek/terra/api/properties/Context.java`

`Context` currently maintains both:
- `Map<Class<? extends Properties>, Properties> map` — used by `put(Properties)` and `getByClassName()`
- `Properties[] list` — used by `put(PropertyKey, T)` and `get(PropertyKey)`

All addon code uses the `PropertyKey` path. The `HashMap` is legacy overhead. Remove it; update `getByClassName()` to iterate the array instead.

```java
public class Context {
    private static final AtomicInteger size = new AtomicInteger(0);
    private static final Map<Class<? extends Properties>, PropertyKey<?>> properties = new HashMap<>();
    private Properties[] list = new Properties[size.get()];

    // Remove: private final Map<Class<? extends Properties>, Properties> map

    public Properties getByClassName(String className) {
        for (Properties p : list) {
            if (p != null && p.getClass().getName().equals(className)) return p;
        }
        return null;
    }
    // ... rest unchanged
}
```

**Note:** The `put(Properties)` overload (without PropertyKey) is used in some addon event handlers. These need to be updated to use `PropertyKey`-based puts, or the map can be kept but only for the legacy `put(Properties)` path. Audit all `context.put(properties)` callsites (without PropertyKey) before removing the map.

---

## Phase 2: Build Infrastructure — Tectonic Publishing

Tectonic currently is not published to Repsy or a local Maven repo, so Terra cannot consume local Tectonic builds. This must be set up before Phase 1 changes can be tested end-to-end.

### 2A — Tectonic: Add Repsy + local Maven publishing

**Reference:** Terra's existing publishing setup in `c:\Projects\Terra\buildSrc\` and `build.gradle.kts` files.

In Tectonic's `build.gradle.kts` (root or per-module):

```kotlin
publishing {
    repositories {
        // Local Maven (~/.m2) for offline/fast iteration
        mavenLocal()

        // Repsy — mirror Terra's existing config
        maven {
            name = "Repsy"
            url = uri("https://repo.repsy.io/mvn/${System.getenv("REPSY_USERNAME")}/tectonic")
            credentials {
                username = System.getenv("REPSY_USERNAME")
                password = System.getenv("REPSY_PASSWORD")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.dfsek.tectonic"
            // artifactId from module name
            version = project.version.toString()
        }
    }
}
```

**Version:** Bump to a `-SNAPSHOT` or `-local` suffix to distinguish from upstream releases (e.g., `current_version-memory-opt-SNAPSHOT`).

### 2B — Terra: Update Tectonic dependency resolution

**File:** `buildSrc/src/main/kotlin/Versions.kt` and the relevant `build.gradle.kts` files.

Add local Maven and Repsy-tectonic to the repository list:

```kotlin
// In settings.gradle.kts or root build.gradle.kts dependencyResolutionManagement:
repositories {
    mavenLocal()  // picks up local Tectonic builds first
    maven {
        name = "RepsyTectonic"
        url = uri("https://repo.repsy.io/mvn/${providers.environmentVariable("REPSY_USERNAME").get()}/tectonic")
    }
    // ... existing repos
}
```

Update `Versions.kt` with the new Tectonic version constant to match whatever version is published in 2A.

**Note on `mavenLocal()` ordering:** Place `mavenLocal()` before other repositories so local builds take precedence during development. Remove or reorder for production CI builds.

---

## Execution Order

```
Phase 0 (complete):  ORIGEN2 pack cleanup — dead yml files removed
                     72 files deleted, pipeline reachability confirmed

Phase 1A (Terra):    Split BiomeNoiseProperties → BiomeNoiseSamplers + BiomeNoiseProperties
                     Prerequisite for safe Tectonic caching of noise config objects
                     Files: BiomeNoiseProperties.java, BiomeNoiseConfigTemplate.java,
                            NoiseChunkGenerator3DAddon.java, Sampler3D.java,
                            ChunkInterpolator.java, ElevationInterpolator.java,
                            LazilyEvaluatedInterpolator.java

Phase 1B (Tectonic): Session-scoped type-load cache in ConfigLoader
                     Files: ConfigLoader.java, AbstractConfigLoader.java
                     Test: load CHIMERA alone, verify biome count matches,
                           confirm no duplicate sampler construction via logging

Phase 1C (Terra):    PaletteHolder interning post-load
                     Files: NoiseChunkGenerator3DAddon.java, PaletteHolder.java
                     Requires: getPalettes() accessor on PaletteHolder

Phase 1D (Terra):    Context dual-storage cleanup
                     Files: Context.java
                     Requires: audit all put(Properties) callsites first

Phase 2A (Tectonic): Add Repsy + mavenLocal publishing to Tectonic build
                     Files: build.gradle.kts (root + modules), settings.gradle.kts

Phase 2B (Terra):    Update Terra to resolve Tectonic from mavenLocal + Repsy
                     Files: buildSrc/src/main/kotlin/Versions.kt,
                            settings.gradle.kts or root build.gradle.kts
```

**Recommended parallel start:** Begin Phase 1A (Terra) and Phase 1B (Tectonic) simultaneously, as they are independent. Phase 2A/2B can be done in parallel with 1A/1B since publishing infrastructure doesn't depend on the code changes.

Phase 1C and 1D are lower priority and can follow after 1A+1B are validated.

---

## Expected Outcome

The session cache in Tectonic (1B) is the single highest-impact change. For a pack like ORIGEN2 where hundreds of biomes inherit `terrain.sampler` from a shared abstract parent:

- **Before:** ~700 separate Sampler tree allocations for the same logical sampler config
- **After:** 1 canonical Sampler tree instance, shared by reference across all inheriting biomes

Combined with the ORIGEN2 pack cleanup (Phase 0), the target is to bring CHIMERA's heap footprint within 2× of the standard overworld config rather than 3.5×, making it loadable alongside the other packs on a machine with `-Xmx8G` and a reasonably-sized paging file.

---

## Files / Repos Involved

| Repo | Local path | Remote |
|---|---|---|
| Terra | `c:\Projects\Terra` | GitHub (primary) |
| Tectonic | `c:\Projects\Tectonic` | GitHub |
| ORIGEN2 pack | `c:\Projects\ORIGEN2` | — |
| TerraOverworldConfig | `c:\Projects\TerraOverworldConfig` | — |
