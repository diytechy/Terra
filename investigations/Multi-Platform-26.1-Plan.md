# Multi-Platform Support Plan — Minecraft 26.1

**Goal:** bring `ForceChimeraPull` to ship Allay, Minestom, Fabric, and NeoForge
builds aligned with the Minecraft Java Edition 26.1 release line.
**Non-goals:** Quilt, Sponge, and Forge-classic (Forge does not exist for the
26.1 line — NeoForge took over). These remain `.disabled`, out of scope.
**Anchor docs:** [DisabledPlatformsAnalysis.txt](DisabledPlatformsAnalysis.txt) (existing per-platform breakdown), [Branch-vs-Upstream-1.21.11.md](Branch-vs-Upstream-1.21.11.md) (where this branch sits relative to upstream).
**Verified at:** 2026-05-18 against upstream maven/release feeds.

---

## ⏱ Progress update — 2026-05-28 (branch `Fabric`)

The plan below was written assuming everything was still `.disabled`. Most of Phases 0–3 have
since landed. **Current reality:**

> ⚠️ **NO RUNTIME VERIFICATION YET ON ANY NEWER PLATFORM.** Allay, Minestom, Fabric, and NeoForge are
> wired into the build graph and Fabric + NeoForge compile green, but **none has been launched,
> smoke-tested, or world-gen-verified** at runtime. "Build green" ≠ "works" — and the compile-time
> mixin AP is disabled, so wrong mixin targets only surface at world-load. Treat every ✅ below as
> "code/build complete," not "runtime-confirmed," until the per-phase smoke-test boxes are checked.

- ✅ **Phase 0 (prep) done:** architectury **dropped — Path A chosen** (architectury never ported
  to 26.1, issue architectury-api#704; see [Versions.kt](../buildSrc/src/main/kotlin/Versions.kt) comment).
  Forge maven repo removed, **NeoForged maven repo added** in
  [DependencyConfig.kt](../buildSrc/src/main/kotlin/DependencyConfig.kt). Fabric uses
  `net.fabricmc.fabric-loom` `1.16.2` directly.
- ✅ **Phase 2 (Minestom) re-enabled:** `platforms/minestom` is ACTIVE, `Minestom.minestom =
  "2026.05.17c-26.1.1"`. (Full green build / smoke test not re-verified this session.)
- ✅ **Phase 3 (Fabric) — core complete:** `platforms/fabric`, `mixin-common`, `mixin-lifecycle`
  are ACTIVE; **`:platforms:fabric:build` is GREEN**. The yarn→Mojang source migration is done, and
  the two runtime-critical mixins + dimension overrides were fixed this session (see
  [Fabric-26.1-Runtime-Mixins-and-Dimension-Plan.md](Fabric-26.1-Runtime-Mixins-and-Dimension-Plan.md)
  and [Fabric-Yarn-to-Mojang-Migration.md](Fabric-Yarn-to-Mojang-Migration.md)). **Remaining:**
  runtime verification (launch + world-gen) and `release.yml` artifact wiring.
- ⏳ **Phase 1 (Allay):** `platforms/allay` is ACTIVE (`Allay.api = "0.20.0"`), but the 0.20.0-resolves
  check and smoke test are not confirmed here.
- ✅ **Phase 4 (NeoForge) — build green (branch `NeoForge`, 2026-05-28).** `platforms/neoforge` is
  ACTIVE and **`:platforms:neoforge:build` is GREEN** alongside Fabric. Implemented via **Path A1**:
  `mixin-common` + `mixin-lifecycle` were made loader-neutral and are consumed by neoforge through
  `shadedImplementation` (ModDevGradle consumes the fabric-loom project deps fine). The stale Forge
  scaffolding was deleted and replaced with thin glue — `NeoForgePlatform extends LifecyclePlatform`
  (`ModList`/`FMLPaths`), `@Mod NeoForgeEntryPoint` with the cloud-neoforge command manager,
  `NeoForgeAddon`. The shared `terra.common`/`terra.lifecycle` mixin configs are declared in
  `neoforge.mods.toml`, and a `META-INF/accesstransformer.cfg` provides the NeoForge equivalent of the
  Fabric accesswidener (`Biome.ClimateSettings` → public). The fat jar bundles 102 mod + 16 lifecycle +
  4 neoforge classes, both mixin configs, the AT, and the manifest (verified by unzip). **Remaining:**
  runtime verification (cannot launch here; 26.1 NeoForge is a moving beta) and `release.yml` wiring.
- ⏳ **Phase 5 (release integration):** not started.

Versions also drifted from the §0/§3.1 snapshots: `fabricLoader = 0.19.2` (not 0.18.4),
`fabricAPI = 0.149.1+26.1.2`, `cloud-fabric = 2.0.0-beta.16`. Treat §3.1's diff as historical.

---

## 0. Version naming — read this first

Mojang **renamed Minecraft Java Edition** in March 2026 to a
`year.drop.hotfix` scheme. **`1.21.x` is dead.** The current line is `26.1.x`.

| Concept | What it's called | Example |
|---|---|---|
| Minecraft Java Edition | `26.1`, `26.1.1`, `26.1.2` | latest stable: **26.1.2** (Apr 9 2026) |
| Paper / Bukkit coord | `26.1.x` | `io.papermc.paper:paper-api:26.1.2-R0.1` (and the new Paper dev-bundle scheme) |
| Fabric Loader | in use: **`0.19.2`** (plan was written at 0.18.4) | `net.fabricmc:fabric-loader:0.19.2` |
| Fabric Loom plugin | **new coord**: `net.fabricmc.fabric-loom`, in use **`1.16.2`** | replaces `fabric-loom-remap` / `fabric-loom` |
| Yarn mappings | **DEPRECATED** for 26.1 | not used — Mojang mappings only |
| NeoForge | `26.1.<patch>.<release>-<channel>` (4 components) | latest: **26.1.2.59-beta** |
| Minestom | `<yyyy.mm.dd>-<mc>` | latest 26.1-line: **2026.05.17c-26.1.1** |
| Allay (Bedrock only) | own SemVer | latest stable: 0.13.0 (Bedrock 1.21.80–1.26.20) |

**MC 26.1 is also the first unobfuscated release.** Mojang ships parameter
names in the server jar, so:
- Yarn/Intermediary mappings are no longer needed.
- Fabric Loom no longer remaps — mods build against Mojang's official names directly.
- NeoForge dropped Parchment mappings (parameter names are already there).
- Paper dropped Spigot reobfuscation (already in motion since 1.20.5; complete at 26.1).
- This is a **breaking change for every mod's source code** — class/method names that came from yarn now have to use Mojang's names.

The whole 26.1 ecosystem **requires Java 25.** Your branch is already on JDK 25 — no change needed there.

---

## 1. Current state

> Updated 2026-05-28. "build script" = whether `platforms/<p>/build.gradle.kts` is active (vs `.disabled`).

| Platform | Build script | Versions.kt block | Effective target | Action |
|---|---|---|---|---|
| **Bukkit** | active | `Bukkit { minecraft = "26.1", paper = "26.1.1.build.+" }` | 26.1 | Reference platform. Verify it tracks 26.1.2. |
| **CLI** | active | `CLI {}` | n/a | Headless. No MC binding. |
| **Allay** | active | `Allay { api = "0.20.0" }` | Bedrock 1.26.x | See §2.1 — confirm 0.20.0 resolves + smoke test. |
| **Merged** | active | n/a | n/a | Aggregator project. |
| **Fabric** | **active** ✅ | `Mod.minecraft = "26.1.2"`, `fabricLoader = "0.19.2"`, `fabricLoom = "1.16.2"` (yarn deleted), `Fabric.fabricAPI = "0.149.1+26.1.2"`, `cloud = "2.0.0-beta.16"` | 26.1.2 | **Build green; runtime verify + release.yml left.** See §2.3. |
| **Minestom** | **active** ✅ | `Minestom.minestom = "2026.05.17c-26.1.1"` | 26.1.1 | Re-enabled + bumped. Confirm green build + smoke test. |
| **Forge** | `.disabled` | commented out | dead | **Out — replaced by NeoForge.** |
| **NeoForge** | `.disabled` ❌ | **block present**: `neoForge = "26.1.2.59-beta"`, `cloud = "2.0.0-beta.15"`, `modDevGradle = "2.0.141"` | needs platform rewrite | **Next up.** Dir still holds stale Forge scaffolding. See §2.4. |
| **mixin-common** | **active** ✅ | no architectury (Path A) | 26.1.2 | Re-enabled; part of the green Fabric build. |
| **mixin-lifecycle** | **active** ✅ | no architectury (Path A) | 26.1.2 | Re-enabled; part of the green Fabric build. |
| Quilt, Sponge | `.disabled` | both commented out | — | **Out of scope.** |

[settings.gradle.kts:24](settings.gradle.kts#L24) excludes Minestom-example with a stale "requires Java 25" comment — the whole project already targets Java 25.

---

## 2. Per-platform approach

### 2.1 Allay (different versioning universe — verify scope first)

**Important:** Allay is a **Bedrock Edition** server. It tracks Bedrock
protocol versions (currently 1.26.x Bedrock), which are **not the same** as
Java Edition 26.1. There is no such thing as "Allay for MC Java 26.1."

What the current branch has:
- `Versions.Allay.api = "0.20.0"` — note the public Allay GitHub only lists
  **0.13.0** as latest stable (May 2025); 0.20.0 may be a custom build or
  a misconfigured pin. **First action: verify 0.20.0 actually resolves.**
- The Allay platform code already compiles and was modified locally per the
  diffstat in [Branch-vs-Upstream-1.21.11.md](Branch-vs-Upstream-1.21.11.md).

**Required:**
1. Confirm `org.allaymc.allay:api:0.20.0` resolves from the configured Maven
   repos. If it doesn't, drop to the latest publicly-released Allay
   (0.13.0 or whatever has shipped by the time you do Phase 1).
2. Smoke test by starting an Allay server, loading a minimal pack, generating
   a chunk.
3. Add the Allay jar to release.yml's artifact list.

**Decision needed from you (Open Question 1):** since Allay doesn't have a
"26.1 release", what does "Allay support" mean for this plan? Two reasonable
interpretations:
- **(a)** Ship whatever current Allay stable exists, regardless of Java
  Edition number — Allay's Bedrock targeting is independent.
- **(b)** Drop Allay from the 26.1 deliverable; revisit when/if Allay aligns
  Bedrock with the next Java Edition drop.

The plan currently assumes **(a)**.

**Difficulty: Low** (under assumption (a)).

---

### 2.2 Minestom (re-enable + version bump)

Minestom released 26.1.1 support on **May 17, 2026**. The coord is
`net.minestom:minestom:2026.05.17c-26.1.1`. The 26.1 branch has been merged
into Minestom main.

**Required:**
1. Bump `Versions.Minestom.minestom` from `"2025.10.31-1.21.10"` to
   `"2026.05.17c-26.1.1"` (verify by checking [github.com/Minestom/Minestom/releases](https://github.com/Minestom/Minestom/releases) at task time — they tag frequently).
2. Rename:
   - `platforms/minestom/build.gradle.kts.disabled` → `build.gradle.kts`
   - `platforms/minestom/example/build.gradle.kts.disabled` → `build.gradle.kts`
3. Edit [settings.gradle.kts:24](settings.gradle.kts#L24) — uncomment the `:platforms:minestom:example` line and delete the stale Java-25 comment.
4. Remove the now-redundant `options.release = 25` block from `platforms/minestom/build.gradle.kts` (root sets it project-wide).
5. **Audit local Minestom edits for unobfuscation impact.** The diffstat
   shows local edits to `TerraMinestomPlatform.java`, `MinestomBiomeLoader.java`,
   `MinestomUserDefinedBiomeFactory.java`, the four sound/particle/biome
   templates, `KeyLoader.java`, `RGBLikeLoader.java`,
   `VanillaBiomeProperties.java`. These were written against the
   1.21.10/1.21.11 Minestom API; the 26.1 line has both Minecraft-side
   class renames (unobfuscation) and Minestom-side API churn.
6. Smoke test: build, start a Minestom example server, generate a chunk.

**Risk:** Minestom has the highest API drift per MC release. The 1.21.10 →
1.21.11 → 26.1.1 path is **two API jumps**, not one. Budget 1 day for
compile-error chasing.

**Difficulty: Medium-High.**

---

### 2.3 Fabric (substantial rewrite — yarn is gone)

This is more than a `.disabled` rename. The Fabric ecosystem moved to
Mojang mappings + a new loom plugin in the 26.1 release. The existing
build script and source code are written against the yarn-mapped world.

**Required:**

1. **Update `Versions.kt` Fabric/Mod blocks:**
   ```kotlin
   object Fabric {
       const val fabricAPI = "<resolve from fabricmc.net/26.1 release notes>"
       const val cloud = "2.0.0-beta.13"  // confirm cloud-fabric has a 26.1 release
   }
   object Mod {
       const val mixin = "0.16.5+mixin.0.8.7"   // verify still valid for 26.1
       const val mixinExtras = "0.5.0"
       const val minecraft = "26.1.2"           // was "1.21.11"
       // const val yarn = ...                   // DELETE — yarn is deprecated
       const val fabricLoader = "0.18.4"        // was "0.18.3"
       // architecturyLoom / architecturyPlugin only if architectury has released 26.1 — see §3.2
   }
   ```
2. **Migrate the build script to the new loom plugin.** Rewrite
   `platforms/fabric/build.gradle.kts.disabled` to use:
   - `id("net.fabricmc.fabric-loom") version "<26.1 loom version>"` (replacing the old `fabric-loom-remap` / `fabric-loom`)
   - `implementation(...)` in place of `modImplementation(...)`
   - `compileOnly(...)` in place of `modCompileOnly(...)`
   - `jar` task in place of `remapJar` (no remapping happens any more)
   - **Drop** `mappings("net.fabricmc:yarn:...")` — Mojang mappings are used directly.
3. **Migrate Fabric source code from yarn names to Mojang names.** The
   1.21.11 Fabric blog post and the [Fabric porting docs](https://docs.fabricmc.net/develop/porting/) list the renames. Key files to audit:
   - `platforms/fabric/src/main/java/com/dfsek/terra/fabric/FabricPlatform.java`
   - `platforms/fabric/src/main/java/com/dfsek/terra/fabric/FabricAddon.java`
   - `platforms/fabric/src/main/java/com/dfsek/terra/fabric/FabricEntryPoint.java`
   - `platforms/mixin-common/src/main/java/com/dfsek/terra/mod/generation/TerraBiomeSource.java` (was modified locally)
   - `platforms/mixin-common/src/main/java/com/dfsek/terra/mod/ModPlatform.java` (was modified locally)
   - All mixin classes under `platforms/mixin-common/...mixin/...` and `platforms/mixin-lifecycle/...` — mixin `@At` targets reference Minecraft class/method names that **all changed** at unobfuscation.
   - `terra.accesswidener` files — these reference yarn names and need to be rewritten with Mojang names.
4. **Resolve mixin-common / mixin-lifecycle** (§3.2). Fabric depends on
   both, so they need re-enabling and 26.1 fixes first.
5. Smoke test: build, drop into a Fabric 26.1 server's `mods/`, generate a chunk.

**Risk:** This is the highest-churn platform. Yarn name → Mojang name is
mechanical but **every mixin signature, every reflection call, every class
import in the platform code is potentially broken.** Architectury may also
not have a 26.1 release yet — see §3.2.

**Difficulty: High** (was Medium when I assumed yarn-and-loom continuity).

---

### 2.4 NeoForge (new platform; 26.1 is still beta)

**Status: feasible, but consumer-facing beta.** Per neoforged.net, NeoForge
26.1 is in beta. Latest version is **26.1.2.59-beta** for MC 26.1.2. The
4-component scheme is `<mcMajor>.<mcDrop>.<mcHotfix>.<neoforgeRelease>-<channel>`.

**Required:**

1. **Source seed.** Upstream Terra's `dev/neoforge` branch is on MC 1.21.1
   (Sep 2024, ~20 months stale). The classes are usable scaffolding but
   the MC API drift between 1.21.1 and 26.1.2 is large, and 26.1 also
   brings the unobfuscation rewrite. Seed via:
   ```
   git checkout TerraGit/dev/neoforge -- platforms/neoforge/
   ```
   Then plan on rewriting most of `ForgePlatform.java`, `BiomeUtil.java`,
   `NoiseConfigMixin.java`, and `AwfulForgeHacks.java`.

2. **Add `Versions.NeoForge` block** to [buildSrc/src/main/kotlin/Versions.kt](buildSrc/src/main/kotlin/Versions.kt):
   ```kotlin
   object NeoForge {
       const val neoForge = "26.1.2.59-beta"   // verify latest at task time
       const val cloud = "2.0.0-beta.13"        // verify cloud-neoforge for 26.1
       // burningwave / yarnPatch — DROP, both irrelevant in unobfuscated 26.1
   }
   ```

3. **Author `platforms/neoforge/build.gradle.kts`** using ModDevGradle 2.0.141+
   or NeoGradle 7.1.21+ (NeoForge's published recommendation). The
   architectury-loom approach upstream used in 2024 is not the 26.1 path —
   architectury support for 26.1 is uncertain (see §3.2). Plan A is
   **ModDevGradle direct** (no architectury), which produces a working
   NeoForge mod without the cross-platform abstraction layer.

4. **Author `META-INF/neoforge.mods.toml`** (NeoForge's manifest, replacing
   the legacy `META-INF/mods.toml`). Set `neoforge` as a dependency, list
   the entrypoint class, declare `loaderVersion = "[4,)"` or similar.

5. **Rewrite the platform code against Mojang mappings + 26.1 API:**
   - Registry access patterns — Mojang refactored registry access in
     1.21.4 and again in 1.21.6, both of which are pre-26.1.
   - Biome generation — `BiomeGenerationSettings$Builder` and
     `BiomeSpecialEffects` field names are now public Mojang names.
   - `NoiseConfigMixin` — verify the target method still exists at the
     same signature in 26.1; if not, rewrite the injection.
   - `AwfulForgeHacks` reflection — mostly deletable, since 26.1's
     unobfuscation removes the need for reflective access to obfuscated
     fields. Replace with direct calls.

6. Smoke test: build, drop into NeoForge 26.1.2 server's `mods/`, generate a chunk.

**Risk: highest.** Two compounding factors:
- NeoForge 26.1 is still **beta** — neoforged.net itself notes "breaking
  changes are still allowed" between betas.
- The reference scaffolding is 20 months stale.

**Difficulty: High.** Budget 3 days minimum, contingent on NeoForge 26.1
reaching a less-volatile beta or RC.

---

## 3. Cross-cutting requirements

### 3.1 Versions.kt summary diff

```diff
 object Fabric {
-    const val fabricAPI = "0.140.0+${Mod.minecraft}"
+    const val fabricAPI = "<26.1 fabric-api release>"
     const val cloud = "2.0.0-beta.13"
 }
 object Mod {
     const val mixin = "0.16.5+mixin.0.8.7"
     const val mixinExtras = "0.5.0"
-    const val minecraft = "1.21.11"
+    const val minecraft = "26.1.2"
-    const val yarn = "$minecraft+build.3"
-    const val fabricLoader = "0.18.3"
+    const val fabricLoader = "0.18.4"
-    const val architecturyLoom = "1.13.463"
-    const val architecturyPlugin = "3.4.162"
+    // architectury fields — see §3.2; may be removable
 }
 object Minestom {
-    const val minestom = "2025.10.31-1.21.10"
+    const val minestom = "2026.05.17c-26.1.1"
 }
+object NeoForge {
+    const val neoForge = "26.1.2.59-beta"
+    const val cloud = "2.0.0-beta.13"
+}
```

### 3.2 mixin-common / mixin-lifecycle — architectury question

✅ **RESOLVED — Path A taken.** Architectury never shipped a 26.1 release
(issue architectury-api#704 still open as of 2026-05-19), so architectury was
dropped entirely. `mixin-common` and `mixin-lifecycle` are now plain
fabric-loom subprojects (active, part of the green Fabric build); the mixin
annotation processor is wired manually — note `useLegacyMixinAp.set(false)` is
required on unobfuscated 26.1 (see the Fabric migration doc). NeoForge (§2.4)
will likewise use ModDevGradle directly, not architectury.

<details><summary>Original Path A vs B framing (historical)</summary>

- **Path A (cleaner, more work):** Drop architectury. Fabric uses
  `net.fabricmc.fabric-loom` directly; NeoForge uses ModDevGradle/NeoGradle
  directly. The "common mixin" abstraction is rebuilt as a plain Java
  subproject with the mixin annotation processor wired in manually.
- **Path B (faster, fragile):** Stay on architectury, contingent on
  architectury 26.1 releases existing.
</details>

### 3.3 settings.gradle.kts

- Uncomment `include(":platforms:minestom:example")`.
- Delete the stale "minestom requires Java 25" comment.
- `includeImmediateChildren(file("platforms"), ...)` auto-picks up
  `platforms/neoforge` once its `build.gradle.kts` exists.

### 3.4 release.yml artifact list

[.github/workflows/release.yml](.github/workflows/release.yml) currently
publishes bukkit, cli, and (possibly) allay jars. Add:
- `platforms/fabric/build/libs/Terra-fabric-*.jar`
- `platforms/minestom/build/libs/Terra-minestom-*.jar`
- `platforms/neoforge/build/libs/Terra-neoforge-*.jar`

### 3.5 Dependency repositories

✅ **Done** in [buildSrc/src/main/kotlin/DependencyConfig.kt](buildSrc/src/main/kotlin/DependencyConfig.kt):
Fabric Maven (`maven.fabricmc.net`) kept, **NeoForged Maven (`maven.neoforged.net/releases`) added**,
Forge Maven removed, no Architectury Maven (Path A). Minestom resolves without a special repo.

### 3.6 Pack / addon platform-agnosticity

The custom Bukkit NMS post-processors
(`Nether`/`Overworld`/`TheEnd`Process) are Bukkit-API-specific and will
not run on the mod-loader platforms. Two options (open question 3 below):
- Document the divergence; the four platforms produce "raw" terrain
  without the loot/NBT/teleport-target post-fills.
- Port the post-processing to platform-agnostic code in `common/`.
  Significant new work — easily a separate plan.

The Repsy-hosted addons (`dendry-terra`, `bubbles-on-chunk-gen`,
`locator-surface-noise-3d`) live in `common/addons/*` and **should** be
platform-agnostic, but smoke-test them on a non-Bukkit platform during
Phase 3 to confirm no Bukkit-only transitive deps slipped in.

---

## 4. Phased plan

Phases are ordered so each leaves the tree in a buildable state.
**Status markers reflect 2026-05-28 (branch `Fabric`).**

### Phase 0 — Prep ✅ DONE
- [x] **Verify** the listed versions still resolve (re-check NeoForge/Minestom at Phase 4 time — they tag often).
- [ ] Resolve **Open Question 1** (Allay scope) — still open; plan assumes (a).
- [x] Resolve **§3.2 architectury question** → **Path A** (architectury never ported to 26.1).
- [x] Remove the dead Forge Maven repo from [DependencyConfig.kt](buildSrc/src/main/kotlin/DependencyConfig.kt).
- [x] Add the NeoForged Maven repo.
- [~] Confirm `./gradlew build` passes on active platforms — Fabric chain green; Allay/Minestom not re-verified this session.

### Phase 1 — Allay verification ⏳ PARTIAL
- [~] `org.allaymc.allay:api:0.20.0` pinned (resolution not confirmed here).
- [ ] Build `:platforms:allay`; fix any API drift.
- [ ] Smoke test: start Allay server, load pack, generate chunk.
- [ ] Add Allay jar to `release.yml` artifact list.

### Phase 2 — Minestom ⏳ MOSTLY DONE
- [x] Bump `Versions.Minestom.minestom` → `2026.05.17c-26.1.1`.
- [x] Rename `.disabled` files for `:minestom` (active).
- [x] Uncomment include in `settings.gradle.kts`.
- [ ] Confirm green build + smoke test on Minestom example server (not re-verified this session).
- [ ] Add Minestom jar to `release.yml`.

### Phase 3 — Fabric ✅ CORE COMPLETE (build green)
- [x] Resolve architectury question (§3.2) → Path A.
- [x] Update Fabric/Mod blocks in `Versions.kt`; delete `yarn` field.
- [x] Rewrite `platforms/fabric/build.gradle.kts` for `net.fabricmc.fabric-loom` (plain `implementation` / `jar`).
- [x] Rename `.disabled` for `:platforms:mixin-common`, `:mixin-lifecycle`, `:fabric`.
- [x] Rewrite mixin `@At` targets and accesswideners with Mojang names (incl. the two runtime-critical mixins fixed 2026-05-28).
- [x] Migrate yarn-named imports and reflection in platform code.
- [x] `:platforms:fabric:build` green.
- [ ] **Runtime verify** on a Fabric 26.1.x server (launch + Terra world-gen) — still pending.
- [ ] Add Fabric jar to `release.yml`.

### Phase 4 — NeoForge ✅ BUILD GREEN 2026-05-28 (branch `NeoForge`) — runtime unverified
Implemented via Path A1 (shared loader-neutral mixin modules). `:platforms:neoforge:build` green;
fat jar verified to bundle the shared classes + mixin configs + AT. Remaining: runtime smoke test
(can't launch here; moving beta) + `release.yml` wiring. The findings/decisions that drove it:

**Findings this session (decompiled `neoforge-26.1.x-beta-sources.jar`):**
- ✅ **Go/no-go cleared:** `net.neoforged:neoforge:26.1.2.59-beta` resolves and the
  `net.neoforged.moddev` ModDevGradle plugin configures cleanly. Build script enabled
  (`platforms/neoforge/build.gradle.kts`, auto-included by `settings.gradle.kts`). `dependencies`
  resolves the full `:common:implementation:base` graph. **No source compiled yet.**
- ⚠️ **The upstream scaffolding's biome-registration approach is invalid for 26.1.**
  `net.neoforged.neoforge.registries.RegisterEvent` is documented as firing **only for builtin
  registries** (`BuiltInRegistries`). `BIOME`/`DIMENSION_TYPE`/`WORLD_PRESET` are **dynamic/datapack
  registries**, so `RegisterEvent.register(Keys.BIOMES, …)` (as in `ForgeEntryPoint`) will never fire
  for them. `DataPackRegistryEvent.NewRegistry` only registers *new datapack registry types that load
  from JSON* — it cannot inject Terra's programmatically-generated biomes either.
- ✅ **Recommended architecture — reuse the `RegistryDataLoader` mixin logic, not `RegisterEvent`.**
  NeoForge runs the **identical** Minecraft `net.minecraft.resources.RegistryDataLoader` (NeoForge's own
  `DataPackRegistryEvent` imports that very class) with the same Mojang names, and NeoForge supports
  Mixin (`mixin` field in `neoforge.mods.toml`). So the `RegistryLoaderMixin` + `RegistryLoadTaskAccessor`
  + `LifecyclePlatform`/`LifecycleUtil`/`LifecycleBiomeUtil` **logic** is what NeoForge wants — *not* the
  `RegisterEvent`/`RegisterHelper`/`AwfulForgeHacks` machinery (which can't see dynamic registries).
  ⚠️ **But `:platforms:mixin-lifecycle` cannot be depended on as-is**: it is built with the
  `net.fabricmc.fabric-loom` plugin and pulls `fabric-loader` + `cloud-fabric` as `implementation`
  (verified in its `build.gradle.kts`). The *source* of `LifecyclePlatform` etc. has no `net.fabricmc`
  imports, but consuming the module would leak Fabric onto the NeoForge classpath and relies on
  loom's refmap/accesswidener wiring. **Two viable paths (decide before coding):**
  - **A1 — extract a loader-neutral module** (e.g. `:platforms:mixin-mod-lifecycle`) holding the
    Minecraft-only mixins + lifecycle classes, built without a loader plugin, consumed by *both* Fabric
    and NeoForge with each loader's own mixin tooling. Cleanest; touches the Fabric build too.
  - **A2 — copy the mixin + lifecycle classes into `:platforms:neoforge`** and apply them via NeoForge's
    mixin config. Faster, but duplicates code that must stay in sync with Fabric's copy.
  Remaining glue either way: `@Mod` entrypoint (NeoForge constructor injection of
  `IEventBus`/`ModContainer`, *not* `FMLJavaModLoadingContext`), `platformName`/`getDataFolder`, and
  `getPlatformMods()` enumeration via NeoForge's `ModList`. The stale yarn-named `NoiseConfigMixin` and
  `BiomeUtil` here duplicate logic already in `mixin-common`/`mixin-lifecycle` and are likely deletable.
- ❗ Still **runtime-blocked**: cannot launch NeoForge here, and it's a moving beta. Treat any compile
  success as unverified.
- ✅ **Packaging gap FIXED for Fabric (2026-05-28).** Switched the mixin subprojects to
  `shadedImplementation(project(...))` in `platforms/fabric/build.gradle.kts`, so the fat
  `Terra-fabric-*-shaded.jar` now bundles the `com.dfsek.terra.mod.*` (102) + `.lifecycle.*` (16)
  classes, all three `*.mixins.json`, and `terra.accesswidener` (verified by unzip — was 0/0).
  Also corrected `fabric.mod.json` (`minecraft "1.21.10"` → `">=26.1"`, `java ">=21"` → `">=25"`).
  **NeoForge must use the same `shadedImplementation` pattern** so the shared classes + mixin configs
  land in its jar (referenced from `neoforge.mods.toml`). ⚠️ Residual, pre-existing, *all-platform*
  concern: the **plain `jar`** task (which `:merged` consumes via its default `"jar"` override) stays
  thin — only the platform's own classes. The shippable artifact is the `-shaded` fat jar; if `:merged`
  is a real ship path it needs to consume `shadowJar` per platform. Not introduced by this change.
  <details><summary>Original gap (now fixed)</summary>The built Fabric jar
  (`platforms/fabric/build/libs/Terra-fabric-*.jar`) declares `terra.lifecycle.mixins.json` and
  `terra.common.mixins.json` in `fabric.mod.json`, but **bundles neither those configs nor the
  `com.dfsek.terra.mod.*` / `com.dfsek.terra.lifecycle.*` classes** — there is no jar-in-jar, and
  `shadowJar` only shades the `shaded` configuration while `mixin-common`/`mixin-lifecycle` are pulled
  via plain `implementation(project(...))`. The shaded jar has `api`/`config`/`tectonic`/`strata` but
  0 `mod`/0 `lifecycle` classes. Net: the current Fabric jar would fail to load (missing mixin configs
  + handler/target classes). Likely fix: make the mixin subprojects `shadedImplementation` (or add a
  loom `include`/JiJ). The same packaging path must be sorted for NeoForge so the shared mixin classes
  + `*.mixins.json` land in its jar and are referenced from `neoforge.mods.toml`. (Also: `fabric.mod.json`
  still hardcodes `"minecraft": "1.21.10"` — stale, should be 26.1.)</details>

Remaining checklist:
`platforms/neoforge/` currently holds only stale upstream **Forge** scaffolding
(`ForgePlatform`, `ForgeAddon`, `ForgeEntryPoint`, `AwfulForgeHacks`, `BiomeUtil`, `NoiseConfigMixin`,
`neoforge.mods.toml`, `terra.forge.mixins.json`) behind `build.gradle.kts.disabled`.
- [x] NeoForge versions block added (`26.1.2.59-beta` / cloud `2.0.0-beta.15` / ModDevGradle `2.0.141`).
- [ ] Confirm latest NeoForge 26.1.x.y-beta on `maven.neoforged.net` (bumps weekly).
- [ ] Seed `platforms/neoforge/` from `TerraGit/dev/neoforge`.
- [ ] Add `Versions.NeoForge` block.
- [ ] Author `platforms/neoforge/build.gradle.kts` using ModDevGradle (Path A).
- [ ] Author `META-INF/neoforge.mods.toml`.
- [ ] Rewrite platform code against Mojang names + 26.1 registry API.
- [ ] Drop `burningwave` reflection — unobfuscation makes it unnecessary.
- [ ] Smoke test on NeoForge 26.1.x server.
- [ ] Add NeoForge jar to `release.yml`.

### Phase 5 — Release integration
- [ ] Confirm `release.yml` builds and uploads 5 platform jars per VIBE tag (bukkit, cli, allay, fabric, minestom, neoforge).
- [ ] Verify Repsy publish workflow still publishes `:common:*` artifacts unchanged.
- [ ] Update `CONTRIBUTING.md` to reflect the new 26.1 build matrix.
- [ ] Add a note that consumers need **Java 25** runtime.

---

## 5. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| NeoForge 26.1 still in beta and breaking weekly | High | Phase 4 jar may not be ship-stable | Pin a specific beta, accept the risk in release notes, or hold Phase 4 for a stable. |
| Architectury hasn't shipped 26.1 support | Medium | Forces Path A in §3.2, ~1 extra day of work | Verify in Phase 0. |
| Yarn → Mojang mapping migration uncovers far more code-drift than expected | Medium-High | Phase 3 stretches to a week | Time-box; ship Phases 0–2 + 4 separately if Phase 3 blocks. |
| Allay 0.20.0 doesn't resolve (only 0.13.0 is public) | Medium | Allay build breaks | Drop to public latest; surface as Open Question 1 first. |
| Custom Bukkit NMS post-processors create behaviour gap on non-Bukkit platforms | Confirmed | Non-Bukkit terrain lacks vanilla-fill behaviour | Decision pending — Open Question 3. |
| Minestom API moved more between 1.21.10 → 26.1.1 than the diffstat hints | Medium | Phase 2 ~2 days instead of 1 | Time-box; the work is mechanical even if voluminous. |
| Repsy-hosted addons have Bukkit-only transitive deps | Low | Fabric/NeoForge/Minestom builds shade in Bukkit classes | Phase 3 smoke test catches this; gate the addons platform-side if needed. |
| Java 25 runtime requirement narrows consumer base | Confirmed | Some users can't run the mod | Document in release notes; this is an upstream-wide change driven by 26.1, not a local choice. |

---

## 6. Open questions

1. **Allay scope.** Allay tracks Bedrock, not Java 26.1. Two interpretations
   in §2.1 — please pick:
   - (a) Ship Allay aligned with whatever current Allay stable exists, regardless of MC Java numbering.
   - (b) Drop Allay from the 26.1 deliverable.
2. **Distribution channels for mod-loader platforms.** Currently
   `release.yml` produces GitHub Releases only. Do you also want
   Modrinth / CurseForge publishing for Fabric and NeoForge? (Important
   because Modrinth's API is the canonical discovery path for Fabric mods.)
3. **Behavioural parity for custom Bukkit NMS post-processors on the
   mod-loader platforms.** Document the gap, or port to `common/`? The
   port is a separate plan-sized effort.
4. ~~**Architectury Path A vs Path B (§3.2).**~~ ✅ **RESOLVED → Path A** (architectury
   never shipped 26.1; dropped entirely).

---

## 7. Out of scope

- **Quilt** — version constants commented out, project health uncertain.
- **Sponge** — version constants commented out, last targeted MC 1.17.1.
- **Forge-classic** — does not exist for 26.1, replaced by NeoForge (§2.4).
- **Bedrock-Java parity** — Allay's Bedrock target ≠ Java 26.1. Don't
  conflate.
- **Java < 25 support** — MC 26.1 itself requires Java 25; not a local
  choice we can revert.

Each of these is straightforward to add to a future plan if priorities
change.

---

## Sources verified for this plan (2026-05-18)

- [Minecraft Java Edition 26.1.2 — Mojang](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1-2)
- [Fabric for Minecraft 26.1 — FabricMC](https://fabricmc.net/2026/03/14/261.html)
- [NeoForge for Minecraft 26.1 — NeoForged](https://neoforged.net/news/26.1release/)
- [Minestom releases — GitHub](https://github.com/Minestom/Minestom/releases)
- [Allay releases — GitHub](https://github.com/AllayMC/Allay/releases)
- [PaperMC 26.1 announcement](https://papermc.io/news/26-1/)
