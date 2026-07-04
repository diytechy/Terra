# Terra Reconstruction Inventory (WI-2.1)

**Repo:** `C:\Projects\Terra`
**Base:** local `master` == `Polyhedral/master` @ `25d510156`
**Target:** local `main` @ `6118e3dd0` (tag `VIBE-20260628-6118e3dd0`, == `origin/main`) — owner states this contains ALL his changes.
**Method:** read-only inspection via `git diff master...main`, `git log`, `git show`. Working tree left on `master`, clean.
**Ground truth for "actually used":** `C:\Projects\CHIMERA` (config pack, dendry-noise addon) + `C:\Projects\Terra-cloud-proper.patch`.

---

## 1. Full diff summary (`master...main`)

- **319 files changed, +20,594 / −2,227.**
- Status: 96 added, 219 modified, 3 deleted, 1 renamed.
- 35 commits on `main` not on `master` (mostly merge/junk history; the real payload is squash-PR #11).

**By area (insertions dominate; the halving target is obvious):**

| Area | Files | Lines (~ins) | Share of +20.6k |
|---|---|---|---|
| `investigations/` (VIBE planning notes) | 34 | **11,316** | ~55% |
| `docs/` DEPENDENCY_* + CLAUDE.md/SESSION_SUMMARY.md/agents notes | 5 | **1,227** | ~6% |
| mixin-common + mixin-lifecycle (MC 26.2 port + registry-freeze) | 85 | +1,184 / −1,251 | net churn |
| bukkit (NMS registry-freeze fix, spawn null-guards, debug/util) | 33 | +1,062 / −193 | |
| sampler-caching (config-noise-function + pipeline/cache/) | 16 | +1,485 / −87 | |
| CI workflows + build scripts (.bat/.command/.vscode/scripts) | 7 | +479 | |
| NeoForge platform (new) | 8 | +251 | |
| profiler (api + impl) | 3 | +119 / −35 | |
| Minestom revival | 11 | +116 / −80 | |
| Fabric platform | 3 | +39 / −31 | |
| buildSrc (Versions/Dependency/Distribution/Publishing) | ~8 | version+repo rework | |

> **Planning-notes alone (investigations + docs ≈ 12,500 lines ≈ 61% of insertions) exceed the owner's expected ≥50% drop.**

---

## 2 & 3. Feature areas — description, size, evidence of use, verdict

### A. MC 26.1/26.2 platform port (mixin-common, mixin-lifecycle, bukkit NMS API migration)
Migrates Terra to `net.minecraft` 26.2 APIs (EntitySpawnRequest, DimensionType-as-record with `HolderSet<Block>` infiniburn, etc.). 85 mixin files + bukkit NMS.
**Evidence:** CHIMERA targets MC 26.2; this is the whole point of the fork. **KEEP.**

### B. 26.2 registry-freeze fix (bukkit `AwfulBukkitHacks`, `Reflection`, mixin-lifecycle Registry mixins)
26.2's `MappedRegistry.freeze()` throws "Tags already present before freezing" because Terra force-bound `allTags`. Fix stages tags through the registry's public `bindTags(...)` and lets `freeze()` promote them; removes hand-rolled reflection helpers; adds `TagSet.unbound()`.
**Evidence:** Required for a client to connect to a 26.2 Bukkit/Paper server running any Terra pack (CHIMERA included). **KEEP.**

### C. 26.2 config null-guards (SpawnSettingsTemplate, SpawnTypeConfig — bukkit + mixin twins)
Null-guards optional `spawns`/`costs`/`entries`. Directly fixes an NPE on **CHIMERA's `DESERT_SPIKES_BLACK`** (spawns without costs).
**Evidence:** named in commit; CHIMERA fails to load without it. **KEEP.**

### D. Fabric port (build.gradle, FabricEntryPoint, fabric.mod.json)
Drops architectury, uses fabric-loom directly against unobfuscated 26.2 jar; cloud-fabric points at `2.0.0-beta.17-diytechy`. Small (39/31). Compiles green; runtime unverified (per README).
**Evidence:** not required by CHIMERA (Bukkit is the runtime target). Low cost, coherent, part of "multi-platform" goal. **UNSURE (Peter decides)** — keep only if multi-platform is a real goal; otherwise DROP. Cheap either way.

### E. NeoForge port (new platform, 8 files, +251)
New `platforms/neoforge` via ModDevGradle (Forge maven swapped for NeoForged). Reuses a mixin file renamed from mixin-common's `StateAccessor`. Compiles green; runtime unverified.
**Evidence:** not required by CHIMERA. **UNSURE (Peter decides)** — same rationale as Fabric; keep if multi-platform matters, else DROP.

### F. Minestom revival (11 files)
Bumps Minestom to `2026.05.17c-26.1.1` (lags at 26.1 — no upstream 26.2). Config/biome loader tweaks.
**Evidence:** not required by CHIMERA. Lags the rest of the stack. **DROP** unless Peter explicitly wants Minestom (skeptical default: revived but unused, and version-mismatched).

### G. mavenLocal / addon-artifact resolution (DependencyConfig.kt, DistributionConfig.kt)
Adds `mavenLocal()` + several diytechy Repsy repos; adds `downloadPackFromMaven`/`downloadPackWithFallback` (local m2 → Repsy → GitHub) for packs; `cacheChangingModulesFor(0)` so re-published-same-version addons re-resolve; bubbles marked `isChanging`.
**Evidence:** This is how CHIMERA/dendry/bubbles get into the build. **The pack-download + mavenLocal wiring is KEEP.** BUT see H on the fork proliferation.

### H. Fork/version dependencies (Versions.kt) — MIXED, the patch is decisive
Versions.kt points many deps at diytechy forks: `tectonic 4.3.2-diytechy`, `seismic 2.5.7-PATCHED`, `cloud 2.0.0-beta.16-diytechy` (Bukkit), `2.0.0-beta.17-diytechy` (Fabric/NeoForge), `dendryTerra 1.0.0-BETA-K`, `bubblesOnChunkGen 1.26.2`, plus config-pack versions `chimeraConfig 0.1.0`, `reimagENDConfig 3.0.0`, `tartarusConfig 1.0.0`.
- **`Terra-cloud-proper.patch` reverts `cloud` back to upstream `2.0.0-beta.16` and deletes the diytechy-cloud comment.** The owner kept this patch → **the cloud fork is intended to be DROPPED** (go back to upstream cloud, drop `RepsyCloudMinecraft`/`RepsyCloudMinecraftModded` repos and the `-diytechy` cloud versions where the upstream works).
- `tectonic 4.3.2-diytechy` is load-bearing (Terra calls `beginSession()/endSession()` not in upstream) → **KEEP** (or upstream it later).
- `dendryTerra`, `bubblesOnChunkGen` → **KEEP** (CHIMERA uses `DENDRY` sampler and `bubbles(x,z)` function — verified in `math/samplers/rivers.yml` and `biomes/equations/eq_terraced_mountains.yml`).
- `seismic 2.5.7-PATCHED` → **UNSURE** — verify it's actually needed vs. upstream 2.5.7.
- **Verdict:** KEEP dendry/bubbles/tectonic + config-pack versions; **DROP the cloud fork per the saved patch**; UNSURE on seismic-PATCHED.

### I. CHIMERA config-pack version bumps + pack download (DistributionConfig, Versions.Terra)
Replaces upstream `overworldConfig/defaultConfig=latest` with `chimeraConfig 0.1.0` (+ reimagEND/tartarus). `downloadDefaultPacks` now hard-fails if CHIMERA can't be resolved.
**Evidence:** CHIMERA is the shipped default pack. **KEEP.**

### J. New Terra addons: `locator-surface-noise-3d`, `noise-dendry`, `bubbles-chunk-gen`
- `locator-surface-noise-3d` (11 files): `TERRAIN_SURFACE` / `BELOW_TOP_TERRAIN_SURFACE` / `TOP_TERRAIN_SURFACE` locators. **Used all over CHIMERA** (ore deposits, fossils). **KEEP.**
- `noise-dendry` (build.gradle only — wraps the external dendryterra lib). **KEEP** (CHIMERA `DENDRY` sampler).
- `bubbles-chunk-gen` (build.gradle only — wraps external bubbles lib; bundled, toggleable via config.yml). **KEEP** (CHIMERA `bubbles()` function).

### K. StructureSearchBiomeProvider (biome-provider-pipeline) + stronghold fast-path
New biome provider for fast stronghold-ring searches at world creation.
**Evidence:** CHIMERA's preset uses `structure-search:` (verified in `biome-distribution/presets/CHIMERA.yml`). **KEEP.**

### L. Sampler-caching / performance machinery (config-noise-function + pipeline/cache/, +1,485)
`MultiSlotCacheSampler`, `DeferredExpressionSampler`, `LastValueSampler`, `SamplerComplexityEstimator`, `PackSamplerContext`, `ChunkScopedCacheSampler`, `SamplerReferenceWalker`, `PipelineSamplerAnalysis`, `DelegateSampler`/`SamplerWrapper` API, plus `cache.sampler: 128→512` config bump.
**Evidence:** **CHIMERA does NOT use `type: CACHE` / MULTI_SLOT / DEFERRED / LAST_VALUE in any live config** (only in `.artifacts`/`archive-investigations`). This is the textbook "complexity without benefit" area. **DROP** the speculative caching samplers and analysis walkers; **UNSURE** on the plain `CacheSampler` integer-option tweak and the `cache.sampler` bump if benchmarks justify them. Skeptical default: DROP most of it.

### M. Profiler / logging additions (Profiler, Timings, ProfilerImpl, LoggingConfig, TerrainDebug, ChunkQueryLogger, command-profiler)
Extra profiling/debug instrumentation and a `TerrainDebug` feature.
**Evidence:** debug scaffolding, not referenced by CHIMERA runtime. **DROP** (keep out of the clean series; it's dev-only tooling).

### N. CI/build scaffolding (.github/workflows/publish.yml, release.yml; build_all.bat, build_bukkit.bat, build-mac.command, .vscode/launch.json, scripts/check-cloud-updates.sh, gradlew regen)
GitHub publish/release workflows (VIBE-branded jars), convenience build scripts, IDE launch config, cloud-update checker.
**Evidence:** not needed to build/run CHIMERA. Personal convenience + VIBE branding. **DROP** (or keep a minimal publish workflow — UNSURE on that one only).

### O. Planning notes: `investigations/` (34 files, 11,316) + `docs/DEPENDENCY_*` (3) + `CLAUDE.md` + `SESSION_SUMMARY.md`
Pure VIBE planning/analysis prose + one `tesf_size_check.py`.
**Evidence:** zero runtime/build impact. **DROP ALL.** (This is the bulk of the ≥50% drop.)

### P. Misc small edits (README, .gitignore, gradle-wrapper, many 1–3 line whitespace/format churn across api/impl/addons)
Trailing-whitespace reformatting in buildSrc and dozens of files inflates the file count. **DROP the pure-whitespace churn**; fold any genuine 1-liners into the relevant KEEP commit. README update to 26.2 → **KEEP** (small).

---

## Verdict summary

| Area | Verdict |
|---|---|
| A. MC 26.2 platform port (mixins/NMS) | **KEEP** |
| B. 26.2 registry-freeze fix | **KEEP** |
| C. 26.2 config null-guards (spawn/cost/entries) | **KEEP** |
| D. Fabric port | **UNSURE** |
| E. NeoForge port | **UNSURE** |
| F. Minestom revival | **DROP** |
| G. mavenLocal + pack-download wiring | **KEEP** |
| H. cloud fork dep | **DROP** (per saved patch) |
| H. tectonic-diytechy / dendry / bubbles deps | **KEEP** |
| H. seismic-PATCHED | **UNSURE** |
| I. CHIMERA pack version + hard-fail download | **KEEP** |
| J. locator-surface / noise-dendry / bubbles addons | **KEEP** |
| K. StructureSearchBiomeProvider (stronghold fast-path) | **KEEP** |
| L. Sampler-caching/complexity machinery | **DROP** (mostly); UNSURE on cache.sampler bump |
| M. Profiler/debug/TerrainDebug instrumentation | **DROP** |
| N. CI/build scripts/VIBE workflows | **DROP** (UNSURE: minimal publish workflow) |
| O. investigations/ + docs planning notes | **DROP ALL** |
| P. whitespace churn | **DROP**; README bump KEEP |

---

## 4. `dev` branch sanity-check — VERDICT: nothing valuable exists only in `dev`

- `git cherry main dev` reports **28 commits** in `dev` not in `main`.
- These are the WIP/plan predecessors that were **squashed into main's PR #11**: e.g. "WIP Phase 3: stage Fabric build", "WIP Phase 4: seed NeoForge", "Add Minestom 26.1.1", "NeoForge A1 steps", "Fix Fabric packaging gap", "Add BELOW_TOP_TERRAIN_SURFACE locator", "Add sub-resolution biome interpolation", "Fix carving interpolator's y-shift", "Bump CHIMERA to 0.0.x", plus plan .md files.
- **Content check (`git diff main dev`) proves dev is strictly BEHIND main on all code:** for every candidate-unique file, `git diff main dev -- <file>` is empty (identical content) — verified for `BelowTopTerrainSurfaceLocator.java`, `ElevationInterpolator.java` (carving y-shift), `PipelineBiomeProvider.java` (sub-resolution interp). The full `main..dev` diff is **−1,554 / +175**, i.e. dev only *removes* things main has (the registry-freeze fix in `AwfulBukkitHacks`, the 26.2 spawn null-guards, the dependency-management docs) and *re-adds* one file main deleted: `platforms/merged/build.gradle.kts` (a stale merged-platform stub — junk, not wanted).
- **Conclusion: `dev` is superseded junk. Nothing in `dev` needs to be salvaged for the rebuild.** Everything of value already landed in `main`.

---

## 5. Proposed clean commit series (WI-2.2)

Fresh branch off `master` (`Polyhedral/master @ 25d510156`). Feature-by-feature, not history replay. DROP areas are excluded entirely.

1. **build: bump platform stack to MC 26.2 + swap Forge maven for NeoForged**
   Carries: buildSrc `Versions.kt` (Bukkit/Fabric/Mod → 26.2), `settings.gradle.kts` + `DependencyConfig.kt` NeoForged repo swap. (Areas A-prereq, G-partial.) Uses **upstream `cloud`** per the saved patch — no `-diytechy` cloud, no Repsy cloud repos.

2. **feat(bukkit): migrate NMS + mixin platform to MC 26.2 net.minecraft APIs**
   Carries: mixin-common + mixin-lifecycle 26.2 API migration, bukkit NMS API updates (excluding the debug/profiler scaffolding). (Area A.)

3. **fix(bukkit): stage biome tags through public bindTags so 26.2 freeze() succeeds**
   Carries: `AwfulBukkitHacks`, `Reflection`, mixin-lifecycle Registry mixins, `TagSet.unbound()`; prune orphaned reflection proxies. (Area B.)

4. **fix(config): null-guard optional spawn/cost/entries in biome spawn settings (26.2)**
   Carries: SpawnSettingsTemplate + SpawnTypeConfig (bukkit + mixin twins). Fixes CHIMERA `DESERT_SPIKES_BLACK`. (Area C.)

5. **feat(deps): resolve config packs & local addons from mavenLocal → Repsy → GitHub**
   Carries: `DistributionConfig.kt` (`downloadPackFromMaven`/`downloadPackWithFallback`, hard-fail on missing CHIMERA, deleteRecursively refresh), `DependencyConfig.kt` mavenLocal + `cacheChangingModulesFor(0)`, dendry/bubbles/tectonic Repsy repos. (Areas G, I.)

6. **feat(deps): point CHIMERA/reimagEND/tartarus + tectonic/dendry/bubbles at pinned versions**
   Carries: `Versions.Terra.chimeraConfig=0.1.0` etc., `tectonic 4.3.2-diytechy`, `dendryTerra`, `bubblesOnChunkGen`. (Area H-keep.) *(Peter to decide on `seismic-PATCHED`.)*

7. **feat(addon): add locator-surface-noise-3d (TERRAIN_SURFACE / BELOW_TOP / TOP locators)**
   Carries: whole `common/addons/locator-surface-noise-3d`. Used by CHIMERA ores/fossils. (Area J.)

8. **feat(addon): wire dendry-noise and bubbles-chunk-gen bundled addons**
   Carries: `noise-dendry/build.gradle.kts`, `bubbles-chunk-gen/build.gradle.kts` (isChanging), `config.yml` ignored-resources fragment-matching + bubbles toggle, `settings.gradle.kts` module includes. (Areas J, and the config.yml ignored-resources logic.) Used by CHIMERA `DENDRY`/`bubbles()`.

9. **feat(biome): add StructureSearchBiomeProvider for stronghold-ring fast path**
   Carries: `StructureSearchBiomeProvider.java` + minimal pipeline hooks. Used by CHIMERA preset `structure-search:`. (Area K.)

10. **docs: update README for MC 26.2 target and platform status**
    Carries: README only. (Area P-keep.)

**Optional / Peter-decides commits (only if he wants them):**
- 11. `feat(platform): Fabric 26.2 port (fabric-loom, no architectury)` — Area D (UNSURE).
- 12. `feat(platform): NeoForge port via ModDevGradle` — Area E (UNSURE).
- 13. `perf(noise): sampler cache tuning` — only the `cache.sampler` bump / plain CacheSampler int option if benchmarked; leave out MultiSlot/Deferred/LastValue/complexity-estimator (Area L, mostly DROP).

**Explicitly excluded (DROP):** `investigations/**`, `docs/DEPENDENCY_*`, `CLAUDE.md`, `SESSION_SUMMARY.md`, agents notes, `.github` VIBE workflows, `build_*.bat`/`build-mac.command`/`.vscode`/`scripts/check-cloud-updates.sh`, Minestom revival, cloud `-diytechy` fork, profiler/TerrainDebug/ChunkQueryLogger debug instrumentation, `platforms/merged` stub, and all whitespace-only churn.
