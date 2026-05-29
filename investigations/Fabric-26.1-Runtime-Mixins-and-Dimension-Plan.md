# Plan — Fabric 26.1: runtime-critical mixins + dimension overrides

**Branch:** `Fabric` · **Status of build:** `:platforms:fabric:build` is GREEN (commit
`2d2116950`). This plan covers the two remaining correctness gaps. Companion reference:
`investigations/Fabric-Yarn-to-Mojang-Migration.md` (the overall migration doc + API maps).

Two independent work items:
- **Item 1** — make `RegistryLoaderMixin` + `SaveLoadingMixin` actually apply at runtime
  (they compile but their injection points are 26.1-stale placeholders).
- **Item 3** — restore `VanillaWorldProperties` dimension overrides via the 26.1
  `EnvironmentAttribute` system (currently dropped; biome overrides already restored).

> ⚠ The compile-time mixin AP is **disabled** (`useLegacyMixinAp=false`, non-obfuscated MC).
> So a wrong `@At`/`@Inject`/`@Local` will NOT fail compile — it fails at **mixin-apply (world
> load)** with a log line like `Critical injection failure` / `could not find ... in target`.
> Use the runtime log as your validation signal for Item 1.

## Tooling (verified this session)
- **Decompiled MC source** (the key tool for Item 1): `./gradlew :platforms:mixin-lifecycle:genSources`
  then read the decompiled `RegistryDataLoader` / `WorldLoader` / `ReloadableServerResources`
  under the loom cache (`~/.gradle/caches/fabric-loom/.../minecraft-*-sources.jar`, or the IDE
  "attach sources"). This shows the exact internal locals + call order you must target.
- **Signatures**: `javap` against `C:/Users/Peter/.gradle/caches/fabric-loom/26.1.2/minecraft-merged.jar`
  with `C:/Program Files/Amazon Corretto/jdk25.0.3_9/bin/javap`.
- **Build/iterate**: `./gradlew :platforms:fabric:build` (green today). For runtime, launch the
  dev client/server from `platforms/fabric` and create a Terra world.
- Per-module compile (fast): `./gradlew :platforms:mixin-lifecycle:compileJava --console=plain`.

---

# ITEM 1 — Fix the two runtime-critical mixins

Both files carry a `TODO (26.1 runtime)` comment. Goal: Terra biomes/world-presets get
registered and flora injected when a world loads. **Validate by launching and confirming a
Terra world generates Terra biomes** (not just that it compiles).

## 1a. `platforms/mixin-lifecycle/.../mixin/lifecycle/RegistryLoaderMixin.java`  ← worldgen-critical
**Purpose:** intercept dynamic-registry loading to grab the *writable* BIOME / DIMENSION_TYPE /
WORLD_PRESET / NOISE_SETTINGS / MULTI_NOISE / ENCHANTMENT registries **before they freeze**, then
call `LifecyclePlatform.setRegistries(...)` + `LifecycleUtil.initialize(...)` to inject Terra
content. `extractRegistry` also calls `((RegistryHack) reg).terra_bind()` to unfreeze each.

**Why it's broken:** 26.1 made `RegistryDataLoader` async; the internal `Loader` record was
removed (now anonymous `$1/$2/$3` + `LoaderFactory`). The current `@At INVOKE List.forEach
ordinal=1` + `@Local(ordinal=2) List<WritableRegistry<?>>` is a type-ported guess.

**New facts (javap-verified):**
- `RegistryDataLoader.load(ResourceManager, List<HolderLookup.RegistryLookup<?>>, List<RegistryDataLoader.RegistryData<?>>, Executor)` → `CompletableFuture<RegistryAccess.Frozen>` (async).
- A second `load(Map<ResourceKey<? extends Registry<?>>, NetworkedRegistryData>, ResourceProvider, List<HolderLookup.RegistryLookup<?>>, List<RegistryData<?>>, Executor)` exists (network path).
- `RegistryDataLoader.RegistryData<T>` has `.key()` / `.elementCodec()` / `.validator()`.
- `WritableRegistry<T>` has `.key()` (inherited) and `.freeze()`; the `RegistryHack` interface adds `terra_bind()`.

**Steps:**
1. `genSources`, open decompiled `RegistryDataLoader`. Find the **server datapack** `load(...)`
   (the `ResourceManager` overload). Trace how it builds the per-registry writable registries and
   **where it freezes them** (look for a `.freeze()` loop or a `.map(... -> registry.freeze())`).
   The writable registries live in some local collection *before* that freeze — that local is your
   `@Local` target. Note its exact erased type + ordinal from the decompiled locals (or use
   `@Local(type = WritableRegistry.class, ...)` matching, or MixinExtras `@Local` by type).
2. Re-point both `@Inject`s at the real `load(...)` descriptor (server overload). The HEAD inject
   (`loadFromResources`) only needs `List<RegistryData<?>> entries` to detect `Registries.BIOME`.
3. Re-point `beforeFreeze` at the instruction **just before freeze** (an `INVOKE` to `freeze` or
   the `forEach`/stream terminal that finalizes registries), capturing the writable-registry local.
   Update `extractRegistry(List<WritableRegistry<?>>, key)` to match whatever the local actually is
   (could be `List<WritableRegistry<?>>`, a `Map<ResourceKey, WritableRegistry>`, or a list of an
   anonymous holder — adapt `extractRegistry` accordingly; it currently filters by `r.key().equals(key)`).
4. **Strongly consider re-architecting instead of internal-local capture** — it's fragile. Options:
   - Mixin into a *stable* method that receives/returns the writable registries (e.g., the lambda
     that creates each registry, or `RegistryDataLoader.RegistryData.runWithArguments` path), or
   - Investigate a Fabric API hook (e.g. `net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback`)
     for *adding* dynamic entries — confirm whether it permits writing biome entries pre-freeze; if
     so it removes this mixin entirely. (fabric-api is already a dependency.)
5. Validate at runtime: launch, create a Terra world, confirm no `RegistryLoaderMixin` apply
   failure in the log AND that Terra biomes exist (`/locate biome terra:...`). Remove the TODO.

## 1b. `platforms/mixin-lifecycle/.../mixin/lifecycle/SaveLoadingMixin.java`
**Purpose:** `@ModifyArg` on the `ReloadableServerResources.loadResources(...)` call inside
`WorldLoader.load(...)`, to grab the `LayeredRegistryAccess` and call `MinecraftUtil.registerFlora(biomes)`.

**First decision — is it still needed?** `mixin-common`'s `DataPackContentsMixin` already injects at
the RETURN of `ReloadableServerResources.loadResources(...)` and calls `registerFlora` +
`registerBiomeTags` + `registerWorldPresetTags`. Check for **double-registration / redundancy**: if
`DataPackContentsMixin` already covers flora on the load path, **delete `SaveLoadingMixin`** and its
entry in `terra.lifecycle.mixins.json` (look it up; this plan didn't verify the json contents). Only
keep it if it covers a path `DataPackContentsMixin` doesn't.

**If kept — new facts:** yarn `SaveLoading` → Mojang `WorldLoader`. `WorldLoader.load(InitConfig,
WorldDataSupplier, ResultFactory, Executor, Executor)`. The `loadResources` descriptor is the same
one used (and verified) in `DataPackContentsMixin`:
`loadResources(ResourceManager, LayeredRegistryAccess, List<Registry.PendingTags<?>>, FeatureFlagSet,
Commands.CommandSelection, PermissionSet, Executor, Executor)` → `CompletableFuture<ReloadableServerResources>`.
- `genSources`, confirm `WorldLoader.load` actually contains a `loadResources(...)` INVOKE and that
  the `LayeredRegistryAccess` is `index = 1` of that call (adjust index if not). Body already correct
  (`compositeAccess().lookupOrThrow(Registries.BIOME)`).
- Validate: world load succeeds, flora present, no mixin apply failure. Remove the TODO.

---

# ITEM 3 — Dimension overrides via EnvironmentAttributeMap

**File:** `platforms/mixin-common/.../util/DimensionUtil.java` (`createDimension`).
**Current state:** copies the new DimensionType fields (skybox, cardinalLightType, attributes,
timelines, defaultClock) from the default dimension and **drops** the
ultrawarm/natural/bedWorks/respawnAnchorWorks/fixedTime/effects/cloudHeight config overrides.
**Goal:** re-apply those overrides onto the dimension's `EnvironmentAttributeMap` (the 14th ctor arg),
mirroring how `BiomeUtil.createBiome` was restored.

**Pattern (javap-verified):**
```java
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.BedRule;

EnvironmentAttributeMap.Builder attrs = EnvironmentAttributeMap.builder()
    .putAll(defaultDimension.attributes());          // carry vanilla defaults
// then, each only when the config sets it:
attrs.set(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, vanillaWorldProperties.getRespawnAnchorWorks());
attrs.set(EnvironmentAttributes.CLOUD_HEIGHT, vanillaWorldProperties.getCloudHeight().floatValue());
attrs.set(EnvironmentAttributes.BED_RULE,
    vanillaWorldProperties.getBedWorks() ? BedRule.CAN_SLEEP_WHEN_DARK : BedRule.EXPLODES);
// ultrawarm is a combo:
boolean uw = vanillaWorldProperties.getUltraWarm();
attrs.set(EnvironmentAttributes.WATER_EVAPORATES, uw);
attrs.set(EnvironmentAttributes.FAST_LAVA, uw);
attrs.set(EnvironmentAttributes.INCREASED_FIRE_BURNOUT, uw);
// ...build and pass as the `attributes` ctor arg (replaces the current defaultDimension.attributes()):
new DimensionType(..., attrs.build(), defaultDimension.timelines(), defaultDimension.defaultClock());
```

**Config getter → attribute mapping (all `VanillaWorldProperties` getters, null = use vanilla):**

| config getter | type | 26.1 attribute(s) | notes |
|---|---|---|---|
| getRespawnAnchorWorks() | Boolean | `RESPAWN_ANCHOR_WORKS` (Boolean) | 1:1 |
| getCloudHeight() | Integer | `CLOUD_HEIGHT` (Float) | `.floatValue()` |
| getBedWorks() | Boolean | `BED_RULE` (BedRule) | true→`BedRule.CAN_SLEEP_WHEN_DARK`, false→`BedRule.EXPLODES` |
| getUltraWarm() | Boolean | `WATER_EVAPORATES`+`FAST_LAVA`+`INCREASED_FIRE_BURNOUT` (Boolean) | combo; verify against vanilla nether's attribute map |
| getNatural() | Boolean | (combo — see below) | **hard**: natural touched portal/compass/sleep behavior. Inspect vanilla overworld vs nether `attributes()` to find which attrs differ and map. Candidates: `NETHER_PORTAL_SPAWNS_PIGLINS`, `CAN_START_RAID`, sleep/compass attrs. If unclear, leave carried-from-default + document. |
| getFixedTime() | Long | (timeline/clock — see below) | **hard**: fixed time moved to `timelines`/`defaultClock` (`HolderSet<Timeline>` / `Optional<Holder<WorldClock>>`). Not an attribute. Requires building a fixed-time `Timeline`/`WorldClock`; investigate `net.minecraft.world.timeline.*` and `net.minecraft.world.clock.*`. If out of scope, keep `defaultDimension.timelines()`/`defaultClock()` and document the gap. |
| getEffects() | Identifier | `skybox` + `cardinalLightType` (DimensionType ctor args 12–13) | the old "effects" id (overworld/nether/end) selected a renderer; now it's `DimensionType.Skybox` + `CardinalLighting.Type`. Map by reading the matching vanilla dimension's `skybox()`/`cardinalLightType()`, or pick by the effects id. If unclear, keep default. |

**Steps:**
1. Build the `EnvironmentAttributeMap` from `defaultDimension.attributes()` + the easy overrides
   (respawnAnchor, cloudHeight, bedWorks, ultrawarm). Pass it as the `attributes` ctor arg.
2. For `natural`: `genSources` and diff vanilla overworld vs nether `DimensionType` JSON/attributes
   (data/`minecraft/dimension_type/*.json` in the unpacked jar, or read the bootstrap) to learn which
   attributes `natural` now corresponds to; map those. If ambiguous, leave default + note it.
3. For `fixedTime` / `effects(skybox)`: scope decision — implement via timeline/skybox if the pack
   feature matters, else keep default and document. These are the only genuinely involved ones.
4. Remove the "drops those config overrides" comment once done; keep notes for any deliberately-deferred prop.
5. Verify: `:platforms:fabric:build` green; at runtime create a Terra world whose pack sets e.g.
   `bed-works: false` / `respawn-anchor-works: true` / custom `cloud-height` and confirm in-game.

**Cross-check (read these first):** `VanillaWorldProperties.java` (getter names/types),
`MonsterSettingsConfig.java`, and how `createDimension` is called from `PresetUtil.insertCustom`.

---

## Acceptance criteria
- Item 1: `:platforms:fabric:build` green; world loads with **no mixin-apply failures** for
  `RegistryLoaderMixin`/`SaveLoadingMixin` in the log; Terra biomes register and a Terra world
  generates Terra terrain/biomes. TODO comments removed (or mixin deleted if redundant).
- Item 3: `:platforms:fabric:build` green; a pack overriding dimension props shows them in-game;
  any deliberately-deferred prop (likely `fixedTime`, `effects`/skybox, possibly `natural`) is
  documented in code + the migration doc.
- Update `investigations/Fabric-Yarn-to-Mojang-Migration.md` status when each item lands.
