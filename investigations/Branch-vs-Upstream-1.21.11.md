# ForceChimeraPull vs. TerraGit/dev/1.21.11 — Comparison & Impact

**Local branch:** `ForceChimeraPull` (HEAD `b94271da4`)
**Compared against:** `TerraGit/dev/1.21.11` (upstream `db930739d`)
**Merge base:** `c6eb2f49f`

This document summarises how the local branch has diverged from upstream Terra's
1.21.11 migration branch, organised by area, with the *outcome impact* of each
group of changes — i.e. what the divergence actually means for builds,
consumers, and the runtime.

For the inverse question ("what's in upstream that we don't have?"), see the
**[What upstream has that we don't](#what-upstream-has-that-we-dont)** section
at the end.

---

## Headline

`ForceChimeraPull` is no longer a maintenance fork of Terra. It is effectively
a **Bukkit-only distribution of Terra targeting Minecraft 1.21.11 (Paper 26.1)**
with its own pack-publishing pipeline, custom forks of upstream libraries, and
substantial engine modifications focused on terrain/biome sampling and caching.

The branch has **27 non-merge commits** ahead of upstream, touching **~200
files** and adding roughly **~14,000 lines** vs. removing ~600.

Upstream's `dev/1.21.11` branch is a parallel migration — *not* a superset of
the local work. The two branches both reach 1.21.11 by different routes, and
the local branch is generally further along in everything except Minestom and
Fabric, which the local branch has intentionally disabled.

---

## 1. Build system & toolchain

### Changes
- **JDK 25** as both source/target ([build.gradle.kts:18](build.gradle.kts#L18) sets `release.set(25)`, [buildSrc/src/main/kotlin/CompilationConfig.kt](buildSrc/src/main/kotlin/CompilationConfig.kt) switches to `toolchain.languageVersion = 25`). Upstream is still on JDK 21 source-level but bumped CI to Java 25 in `2e2b315ab`.
- **paperWeight** bumped to `2.0.0-beta.21` (upstream beta.19) in both [buildSrc/build.gradle.kts](buildSrc/build.gradle.kts) and [buildSrc/src/main/kotlin/Versions.kt](buildSrc/src/main/kotlin/Versions.kt).
- **Gradle 9.2.1** wrapper — already aligned with upstream.

### Outcome impact
- Local development needs a JDK 25 toolchain *somewhere* Gradle can find it
  — either pre-installed (auto-detected) or downloadable via Foojay. The JDK
  running Gradle itself only needs to be 17+ (Gradle 9.x's minimum). This is
  why the PR-CI workflow on JDK 21 still builds successfully: `setup-java`
  bootstraps Gradle, the toolchain handles compilation.
- The repo cannot be casually rebased onto upstream without preserving the
  Java 25 change; reverting to 21 would break any Java-25-isms in the source.

---

## 2. Dependencies & artifact repositories

### Changes ([buildSrc/src/main/kotlin/Versions.kt](buildSrc/src/main/kotlin/Versions.kt))
| Dependency | Local | Upstream `dev/1.21.11` |
|---|---|---|
| tectonic | **`4.3.2-diytechy`** (custom fork) | `4.3.1` |
| seismic | **`2.5.7-PATCHED`** (custom fork) | `2.5.7` |
| dendryTerra | `1.0.0-BETA-G` *(new)* | — |
| bubblesOnChunkGen | `1.2.2` *(new)* | — |
| Bukkit/Paper | **`26.1` / `26.1.1.build.+`** (Paper's new scheme) | `1.21.11-rc3-R0.1` (legacy scheme) |
| paperWeight | `2.0.0-beta.21` | `2.0.0-beta.19` |
| Bukkit cloud | `2.0.0-beta.15` | `2.0.0-beta.12` |
| multiverse | `5.6.1` | `5.3.0` |
| Allay | `0.20.0` | `0.13.0` |
| Minestom | `2025.10.31-1.21.10` *(disabled)* | `2025.12.20c-1.21.11` |
| Terra packs | `chimeraConfig=0.0.4`, `reimagENDConfig=3.0.0`, `tartarusConfig=1.0.0` | `overworldConfig=latest`, `reimagENDConfig=latest`, `tartarusConfig=latest`, `defaultConfig=latest` |

### Repositories added ([buildSrc/src/main/kotlin/DependencyConfig.kt](buildSrc/src/main/kotlin/DependencyConfig.kt))
- `mavenLocal()` (now first in the resolution order)
- `repo.repsy.io/mvn/diytechy/tectonic` — diytechy Tectonic fork
- `repo.repsy.io/mvn/diytechy/seismic` — diytechy Seismic fork
- `repo.repsy.io/mvn/diytechy/dendryterra`
- `repo.repsy.io/mvn/diytechy/terra-packs`
- `repo.repsy.io/mvn/diytechy/bubblesonchunkgen`

### Outcome impact
- **Hard fork dependency on `diytechy` Repsy artifacts**. The local build cannot
  be reproduced from a clean checkout without network access to
  `repo.repsy.io/mvn/diytechy/*`, since Tectonic and Seismic are both
  customised. The comment in `Versions.kt` is explicit:
  > Requires the diytechy Tectonic branch — Terra calls
  > `ConfigLoader.beginSession()/endSession()` which do not exist in upstream
  > Tectonic.
  So we *cannot* drop back to stock Tectonic 4.3.1 without removing call sites.
- The Bukkit version scheme switch (`1.21.11-rc3-R0.1` → `26.1.1.build.+`)
  reflects Paper's own renumbering. The two branches are pinned to the
  **same Minecraft release** but resolve Paper-API differently — upstream
  uses Paper's old `-rcN-R0.1` snapshot coords; local uses Paper's new
  semver-style coords.
- Pinning pack versions (Chimera 0.0.4, ReimagEND 3.0.0, Tartarus 1.0.0)
  vs. upstream's `"latest"` keeps the local distribution reproducible.

---

## 3. Distribution & publishing

### Changes
- [buildSrc/src/main/kotlin/DistributionConfig.kt](buildSrc/src/main/kotlin/DistributionConfig.kt) gained ~200 lines: `downloadPackFromMaven()` and `downloadPackWithFallback()` helpers that resolve packs from local Maven, then Repsy, then optional GitHub fallback URLs. The default config flow now downloads `com.diytechy.terra.packs:*` artifacts from the `diytechy/terra-packs` Repsy repo.
- [buildSrc/src/main/kotlin/PublishingConfig.kt](buildSrc/src/main/kotlin/PublishingConfig.kt) adds:
  - A `Repsy` publication target (`repo.repsy.io/mvn/diytechy/terra`) alongside the existing solo-studios repo.
  - A **dirty-tree guard** on `publishToMavenLocal` — the task aborts if `git status --porcelain` (excluding untracked) is non-empty, because Terra's BETA version embeds the git short hash and a dirty publish would record a stale hash.
- The CHIMERA pack is now treated as **required** rather than `"latest"`:
  - Commit `d19089491` ("Fail build on missing CHIMERA; bump CHIMERA to 0.0.4") makes a missing CHIMERA pack a build failure.

### Outcome impact
- Builds are now responsible for **pulling and bundling external pack
  artifacts** at build time. This is the mechanism that produces VIBE-branded
  platform jars in the release workflow.
- The dirty-tree guard prevents the long-standing footgun where a
  `publishToMavenLocal` would tag the artifact with a HEAD hash that no
  longer matches the source after the next commit.
- Other consumers of the Terra artifact (e.g. **DendryTerra**,
  **BiomeTool**) pin specific BETA hashes from this pipeline — any change to
  the publication coordinate or format breaks them. See
  [§13](#13-effect-on-downstream-consumers).

---

## 4. CI / CD workflows

### Changes
- **New** [.github/workflows/release.yml](.github/workflows/release.yml) (commit `21a747ab5`) — push-to-`main` workflow that:
  - Builds all platform jars on **JDK 25**.
  - Computes a tag of the form `VIBE-YYYYMMDD-<short-sha>` from the merge commit's date + SHA — so every merge produces a unique sortable release without manual versioning.
  - Publishes the platform JARs as a GitHub Release.
- **New** [.github/workflows/publish.yml](.github/workflows/publish.yml) — Repsy publish workflow.
- Existing [.github/workflows/gradle-build.yml](.github/workflows/gradle-build.yml) pins `setup-java` to JDK 21 on the local branch (upstream bumped to 25 in `2e2b315ab`) — this is the **bootstrap JDK for the Gradle daemon, not the compile JDK**. Compilation goes through the toolchain (§1, [CompilationConfig.kt:33-35](buildSrc/src/main/kotlin/CompilationConfig.kt#L33-L35)), which auto-resolves a JDK 25 from the runner's pre-installed JDKs or via Foojay. So PR CI works as-is.
- Branch convention: `2f932f325 "Change branch name from 'master' to 'main'"` and `release.yml` triggers on both `main` and `master`.

### Outcome impact
- The custom `release.yml` is the **main artifact-delivery pipeline** for this
  fork. Every merge to `main` produces a VIBE-tagged GitHub release with
  built platform jars. This is the user-facing distribution channel.
- The JDK 21 vs JDK 25 split between PR CI and `release.yml` is intentional in
  effect (toolchain abstracts it) but **inconsistent on paper** — aligning
  both workflows on the same `setup-java` version is a cosmetic cleanup, not
  a correctness fix.

---

## 5. Platform support

### Changes (re-named to `.disabled`)
- `platforms/fabric/build.gradle.kts` → `.disabled`
- `platforms/minestom/build.gradle.kts` → `.disabled`
- `platforms/minestom/example/build.gradle.kts` → `.disabled`
- `platforms/mixin-common/build.gradle.kts` → `.disabled`
- `platforms/mixin-lifecycle/build.gradle.kts` → `.disabled`
- [settings.gradle.kts](settings.gradle.kts) excludes `:platforms:minestom:example` with comment *"disabled — minestom requires Java 25"* (irony: the rest of the project already requires Java 25).
- Commit `39e815d9a "Supress broke platforms"` is the deliberate cull.

### Outcome impact
- **Only Bukkit, CLI, Allay, and Sponge** build on this branch. Fabric, Forge,
  Quilt, and Minestom are removed from the build graph — *but their sources
  remain in the tree*. They will rot relative to upstream.
- Any Bukkit-specific change that touches `common/api` may produce changes
  that won't compile against Fabric/Forge if/when they are re-enabled. The
  re-enable cost grows over time.
- Upstream changes to disabled platforms (Minestom 1.21.11 bump, mixin
  build refactors, fabric API/loader bumps) are **not pullable** here without
  also re-enabling the platform.

---

## 6. Bukkit platform additions

### Changes ([platforms/bukkit/](platforms/bukkit/))
- **+948 lines, -39 lines** across 21 files. Net new files:
  - [platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/debug/ChunkQueryLogger.java](platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/debug/ChunkQueryLogger.java) — diagnostic logger for chunk queries.
  - [platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/listeners/ServerLoadListener.java](platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/listeners/ServerLoadListener.java) — defers pack loading.
  - [platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/util/PreExistingWorlds.java](platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/util/PreExistingWorlds.java) — detection of worlds already on disk.
  - [platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/util/SpigotConfigUtil.java](platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/util/SpigotConfigUtil.java) — read Spigot YAML config.
  - [platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/NMSChankLoadListener.java](platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/NMSChankLoadListener.java) *(note: typo "Chank")*.
  - **`platforms/bukkit/nms/.../processors/`** — three new NMS post-processors:
    - `NetherProcess.java` (15 lines)
    - `OverworldProcess.java` (196 lines)
    - `TheEndProcess.java` (251 lines)
- **`NMSInitializer`** rewritten ([platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/NMSInitializer.java](platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/NMSInitializer.java)) — uses `MINIMUM_MAJOR=26, MINIMUM_MINOR=1` instead of a hard-coded `List.of("v1.21.11")`. Survives future Paper minor bumps without code change.

### Outcome impact
- The NMS processors implement **post-generation NBT/loot/structure fill-in**
  for vanilla-like behaviour (referenced by commit `1a0ad7d7e`):
  filling chests and BrushableBlock loot tables, populating beehives, adding
  EndGateway teleport targets, suppressing bedrock at EndCrystals.
  This is significant runtime behaviour that upstream doesn't have at all.
- The `NMSInitializer` version-range scheme means the plugin won't have to be
  patched for every Paper minor release within the 26.x line — a real
  maintainability win over upstream's hard-coded approach.
- The deferred `ServerLoadListener` + `PreExistingWorlds` pair allows manual
  pack loading after server start (commit `978ea12f3 "Supress pack loading so
  it can be done manually."`) — useful for diagnostics but a behavioural
  divergence from upstream that any user-facing docs need to reflect.

---

## 7. Engine: chunk-generator-noise-3d

### Changes (+1024 lines, -158 lines, 18 files)
- New files:
  - [common/addons/chunk-generator-noise-3d/.../TerrainDebug.java](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/TerrainDebug.java)
  - [common/addons/chunk-generator-noise-3d/.../SamplerFloorFeature.java](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/SamplerFloorFeature.java)
  - [common/addons/chunk-generator-noise-3d/.../config/noise/BiomeNoiseSamplers.java](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/config/noise/BiomeNoiseSamplers.java)
  - [common/addons/chunk-generator-noise-3d/.../config/NoiseChunkGeneratorPackConfigTemplate.java](common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/config/NoiseChunkGeneratorPackConfigTemplate.java)
- Heavy rework of:
  - **`ChunkInterpolator`** (+362 lines) — the 3D density interpolation grid.
  - **`Sampler3D`** (+104 lines) and **`SamplerProvider`**.
  - **`NoiseChunkGenerator3D`** (+147 lines).
  - `LazilyEvaluatedInterpolator`, `ElevationInterpolator`, `Interpolator3`, `SlantCalculationMethod`, `PaletteHolder`.

### Outcome impact
- This is **the hot path of terrain generation**. Changes here affect every
  chunk produced — both correctness and performance. Two of the listed
  investigation files
  ([TerrainGenerationPipeline.md](TerrainGenerationPipeline.md),
  [TerrainCachingReference.md](TerrainCachingReference.md)) document the
  intended semantics; together with [Investigations.md](Investigations.md)
  they're the authoritative reference for what the modified pipeline is
  supposed to do.
- Upstream cannot be cleanly merged into this area — any conflict here would
  require manual reconciliation against the pipeline reference docs.
- The pack-level template (`NoiseChunkGeneratorPackConfigTemplate`) means
  pack authors can now configure generator behaviour at the pack level
  rather than per-biome; existing packs that don't use it are unaffected.

---

## 8. Engine: config-noise-function

### Changes (+905 lines, -87 lines, 12 files)
- New samplers:
  - [common/addons/config-noise-function/.../sampler/MultiSlotCacheSampler.java](common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/config/sampler/MultiSlotCacheSampler.java)
  - [common/addons/config-noise-function/.../sampler/LastValueSampler.java](common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/config/sampler/LastValueSampler.java)
  - [common/addons/config-noise-function/.../sampler/DeferredExpressionSampler.java](common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/config/sampler/DeferredExpressionSampler.java)
- New infrastructure:
  - [common/addons/config-noise-function/.../PackSamplerContext.java](common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/PackSamplerContext.java)
  - [common/addons/config-noise-function/.../SamplerComplexityEstimator.java](common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/SamplerComplexityEstimator.java) — analyses sampler graphs to decide caching strategy.
- **`CacheSampler`** completely rewritten (+264 lines) — commit `7ba54f061 "Claude's cache rewrite - UNTESTED"` and follow-ups `07271defe`, `ac8798a53`.

### Outcome impact
- The cache rewrite is one of the **most consequential** changes on the
  branch. `CacheSampler` is invoked per-block in many configurations; any
  correctness regression here corrupts terrain across the board.
- The accompanying notes
  ([cache-sampler-performance-notes.md](cache-sampler-performance-notes.md),
  [sampler-caching-notes.md](sampler-caching-notes.md),
  [sampler-optimization-reference.md](sampler-optimization-reference.md))
  document the design but the commit message explicitly flags it as
  `UNTESTED`. Treat any unexplained generation artifacts on this branch as
  cache-rewrite suspect first.
- `SamplerComplexityEstimator` enables per-graph caching decisions —
  formerly a flat configuration.

---

## 9. Engine: biome-provider-pipeline

### Changes (+1077 lines, -29 lines, 19 files)
- New files:
  - [common/addons/biome-provider-pipeline/.../cache/ChunkGenerationContext.java](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/ChunkGenerationContext.java)
  - [common/addons/biome-provider-pipeline/.../cache/ChunkScopedCacheSampler.java](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/ChunkScopedCacheSampler.java)
  - [common/addons/biome-provider-pipeline/.../cache/PipelineSamplerAnalysis.java](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/PipelineSamplerAnalysis.java)
  - [common/addons/biome-provider-pipeline/.../cache/SamplerReferenceWalker.java](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/cache/SamplerReferenceWalker.java)
  - [common/addons/biome-provider-pipeline/.../StructureSearchBiomeProvider.java](common/addons/biome-provider-pipeline/src/main/java/com/dfsek/terra/addons/biome/pipeline/StructureSearchBiomeProvider.java)
- Heavy rework of `PipelineImpl`, `BiomeChunkImpl`, `BiomePipelineAddon`, `BiomePipelineTemplate`.

### Outcome impact
- The pipeline now has a **chunk-scoped caching layer** that knows which
  samplers are referenced by which pipeline stages
  (`SamplerReferenceWalker` + `PipelineSamplerAnalysis`). This is required
  for the `StructureSearchBiomeProvider` to do correct biome-lookahead
  during structure placement without re-evaluating the whole pipeline.
- Behaviourally relevant for structure spawning (strongholds, villages,
  etc.) — see [Stronghold_search_delay_fix.md](Stronghold_search_delay_fix.md).
- Conflicts with upstream pipeline edits will be hard to resolve; almost
  all of `PipelineImpl` is rewritten.

---

## 10. Engine: biome-provider-extrusion

### Changes (small, but introduces a new extrusion type)
- New files:
  - `extrusion/config/extrusions/ReplaceMaxYSamplerTemplate.java`
  - `extrusion/extrusions/ReplaceMaxYSamplerExtrusion.java`
- Touches `BaseBiomeColumn`, `BiomeExtrusionProvider`, `BiomeExtrusionTemplate`, `ReplaceableBiomeLoader`, `ReplaceExtrusion`.

### Outcome impact
- Adds a new pack-config primitive: replace a biome above a sampler-defined
  max Y. See [ExtrusionYBlend.md](ExtrusionYBlend.md) for the design.
- New addition only — should not affect packs that don't use the new
  extrusion type.

---

## 11. New addons

### Changes
- **[common/addons/locator-surface-noise-3d/](common/addons/locator-surface-noise-3d/)** — new locator addon (TerrainSurfaceLocator + TopTerrainSurfaceLocator). Published as a Repsy artifact in commit `b94271da4`. See [TerrainSurfaceLocatorPlan.md](TerrainSurfaceLocatorPlan.md).
- **`common/addons/noise-dendry/`** — DendryTerra integration build script (consumes `dendryterra` from Repsy).
- **`common/addons/bubbles-chunk-gen/`** — BubblesOnChunkGen integration build script.
- **`common/addons/config-locators/`** — new `SamplerMaxYLocator` template + implementation.

### Outcome impact
- These addons are local-only — they don't exist in upstream and can't be
  upstreamed without first contributing the underlying libraries
  (DendryTerra, BubblesOnChunkGen) which are themselves diytechy projects.
- All three depend on Repsy artifacts; offline builds will fail unless
  artifacts are present in `~/.m2`.

---

## 12. Documentation & investigation files (root)

### Changes
The branch adds **~25 documentation files at the repo root**, including:
- Architecture/pipeline references:
  [TerrainGenerationPipeline.md](TerrainGenerationPipeline.md),
  [TerrainCachingReference.md](TerrainCachingReference.md),
  [CachingBiomeProvider.md](CachingBiomeProvider.md),
  [FeaturePlacement.md](FeaturePlacement.md),
  [biome-climate-properties.md](biome-climate-properties.md).
- Investigation logs:
  [Investigations.md](Investigations.md),
  [TERRA_DEBUG_INVESTIGATION.md](TERRA_DEBUG_INVESTIGATION.md),
  [ConversationAboutQuerySize.txt](ConversationAboutQuerySize.txt),
  [DisabledPlatformsAnalysis.txt](DisabledPlatformsAnalysis.txt).
- Plans / refactor proposals:
  [Paper26.1-Migration-Plan.md](Paper26.1-Migration-Plan.md),
  [MemoryOptimizationPlan.md](MemoryOptimizationPlan.md),
  [OptimizationPlan.md](OptimizationPlan.md),
  [Terra_Refactor_AutoCache.md](Terra_Refactor_AutoCache.md),
  [TerrainDebugPlan.md](TerrainDebugPlan.md),
  [Plan2_PackLevelTerrainSamplers.md](Plan2_PackLevelTerrainSamplers.md),
  [Stronghold_search_delay_fix.md](Stronghold_search_delay_fix.md),
  [ExtrusionYBlend.md](ExtrusionYBlend.md),
  [TerrainSurfaceLocatorPlan.md](TerrainSurfaceLocatorPlan.md).
- Sampler notes:
  [cache-sampler-performance-notes.md](cache-sampler-performance-notes.md),
  [sampler-caching-notes.md](sampler-caching-notes.md),
  [sampler-instantiation-notes.md](sampler-instantiation-notes.md),
  [sampler-optimization-reference.md](sampler-optimization-reference.md),
  [multi-value-samplers-notes.md](multi-value-samplers-notes.md).
- Per-format references:
  [BiomeBlendConfig.txt](BiomeBlendConfig.txt),
  [BlockPlacing.txt](BlockPlacing.txt),
  [MinDensityConfig.txt](MinDensityConfig.txt),
  [TerraProfilerReference.txt](TerraProfilerReference.txt).

### Outcome impact
- These files document the engine-internal changes (§§ 7–10) and are
  load-bearing for understanding the modified behaviour. They are **not
  upstreamable** as-is — upstream's contributing guidelines would not accept
  them at the repo root.
- A stray empty file `bat` (created by `c55b0ff65 "Terrain investigation"`)
  is committed; appears unintentional and could be removed.

---

## 13. Effect on downstream consumers

The Terra artifact produced by this branch is consumed by:
- **DendryTerra** ([C:\Projects\DendryTerra\build.gradle.kts:24-27](C:\Projects\DendryTerra\build.gradle.kts#L24-L27)) — pins exact BETA hashes (e.g. `7.0.0-BETA-ec788bf`).
- **BiomeTool** ([C:\Projects\BiomeTool\build.gradle.kts:155-160](C:\Projects\BiomeTool\build.gradle.kts#L155-L160)) — uses `-BETA-$terraGitHash` interpolation with a hard-coded dash.

### Impact
- Any change to the publication coordinate format
  (e.g. switching `-BETA-<hash>` → `-BETA+<hash>` per SemVer 2.0) would
  break both consumers' resolution simultaneously.
- The custom Repsy publication channel (`repo.repsy.io/mvn/diytechy/terra`)
  is **the** distribution path for these consumers — taking it offline or
  re-pathing it requires lockstep updates downstream.

---

## What upstream has that we don't

Re-checked end-to-end. There are **8 non-merge commits** in
`TerraGit/dev/1.21.11` past the merge base. Status of each:

| Upstream commit | Status here |
|---|---|
| `b04156bb4` "Started migration to 1.21.11" (Bukkit NMS refactors, Versions.kt bumps) | **Already integrated** — per-file diff is empty for the affected NMS files. Versions.kt diverged on top of equivalent changes. |
| `349ce6654` "Adjusted list of supported versions" (`NMSInitializer` → `List.of("v1.21.11")`) | **Superseded** — local switched to a major/minor numeric scheme. |
| `d3429626b` Gradle 9.2.1 | **Already on it.** |
| `04554b886` Minestom 1.21.10 | Not applicable — Minestom disabled. |
| `2e2b315ab` CI Java 25 (`.github/workflows/gradle-build.yml: '21' → '25'`) | Not here. **Cosmetic only** — the local PR-CI workflow pins JDK 21 for `setup-java`, which is the Gradle-daemon bootstrap JDK; compilation goes through the Gradle toolchain and resolves JDK 25 independently. PR builds work as-is. Pulling the upstream bump would just align CI with `release.yml` for consistency. |
| `e99ae48f1` "build: address review comments" (Utils.kt `providers.exec { … }` refactor, Minestom build tweaks) | **Already integrated** for Utils.kt; Minestom parts N/A. |
| `2120dde0d` "WIP on master" (mixin-common/mixin-lifecycle tweaks for 1.21.11) | Not applicable — mixin platforms disabled. |
| `968637223` "feat: update minestom to 1.21.11" | Not applicable — Minestom disabled. |

**Net actionable: nothing required.** The closest candidate is `2e2b315ab`'s
one-line `setup-java` bump from `'21'` to `'25'` in
[.github/workflows/gradle-build.yml](.github/workflows/gradle-build.yml), but
this is cosmetic — the toolchain already provisions JDK 25 for compilation
regardless of what JDK runs the Gradle daemon. Pulling it would only align
the PR workflow with `release.yml` for consistency.

The other small upstream change — Utils.kt's `-BETA-<hash>` → `-BETA+<hash>`
SemVer formatter — would break downstream consumers (see §13) and is **not
recommended**.

---

## Summary

`ForceChimeraPull` is best understood as a **product fork**, not a long-running
feature branch. Its identity as a Bukkit-only, JDK-25, Paper-26.1 distribution
with a custom pack pipeline, custom forks of Tectonic/Seismic, and substantial
engine rework around caching/sampling means that "merging upstream `dev/1.21.11`"
is mostly a no-op: upstream's migration commits are either already integrated,
made obsolete by parallel local work, or target platforms this branch has
disabled.

Nothing from upstream requires a cherry-pick. Future merges should be
evaluated case-by-case against the local engine modifications documented
in §§ 7–10.
