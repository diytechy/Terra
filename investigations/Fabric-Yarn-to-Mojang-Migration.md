# Fabric 26.1 — Yarn → Mojang Source Migration (WIP)

**Branch:** `Fabric`
**Status:** ✅ **`:platforms:fabric:build` is BUILD SUCCESSFUL** — full chain (compile +
mixin refmap + accesswidener + remap + jar) passes for `mixin-common`, `mixin-lifecycle`,
and `fabric`. Jar produced at `platforms/fabric/build/libs/Terra-fabric-*.jar`.
**Remaining: runtime verification** (launch + world-gen) — the mixin injection points below were
re-derived against decompiled/`javap` 26.1 and the build is green, but the compile-time mixin AP
is off so a wrong target only surfaces at world-load. Validate by launching and generating a Terra
world (watch the log for any mixin-apply failure).

## ✅ Two runtime-critical mixins re-derived against decompiled 26.1
The compile-time mixin AP is disabled (see below), so wrong injection points/locals do NOT fail
compile — they fail at mixin-apply (load) time. Both were re-worked against the genSources output:
- **`RegistryLoaderMixin`** — `RegistryDataLoader.load(...)` is now async and the writable registries
  are encapsulated in per-registry `RegistryLoadTask` objects (private `registry` field), frozen
  inside the `thenApplyAsync` lambda by `loadTasks.stream().filter(t -> t.freezeRegistry(...))`.
  Rewritten to (1) flag the server-datapack worldgen load at the HEAD of the `ResourceManager`
  `load(...)` overload (only it carries `Registries.BIOME`), and (2) `@Inject` at HEAD of that
  freeze lambda (`lambda$load$2(List,Map,Void)RegistryAccess$Frozen`, confirmed via
  `javap -p net.minecraft.resources.RegistryDataLoader`) where every registry is fully populated yet
  unfrozen. New `RegistryLoadTaskAccessor` (`@Accessor("registry")`) pulls the writable `MappedRegistry`
  out of each task; `extractRegistry` then `terra_bind()`s and hands them to `setRegistries` +
  `LifecycleUtil.initialize`. ⚠ `lambda$load$2` is a synthetic name — re-derive via `javap` if
  `RegistryDataLoader.load`'s body changes between MC versions.
- **`SaveLoadingMixin`** — **deleted** (removed from `terra.lifecycle.mixins.json`). It was a
  redundant `@ModifyArg` grabbing the `LayeredRegistryAccess` at the `loadResources(...)` call site
  inside `WorldLoader.load` to call `registerFlora`. `mixin-common`'s `DataPackContentsMixin` already
  injects at the RETURN of `ReloadableServerResources.loadResources(...)` itself and calls
  `registerFlora` (+ biome/world-preset tags), covering every caller including `WorldLoader.load`.

## ✅ VanillaBiomeProperties overrides RESTORED via the 26.1 EnvironmentAttribute system
The dropped biome overrides are back in `BiomeUtil.createBiome`: fog/sky/water-fog colors,
music volume, music, particles, ambient sounds are re-applied with
`BiomeBuilder.setAttribute(EnvironmentAttributes.X, value)` (each only when the pack sets it;
vanilla's are carried via `putAttributes(vanilla.getAttributes())`). Type adapters:
`new BackgroundMusic(Music)`, `List.of(AmbientParticle)`, `new AmbientSounds(Optional<Holder<SoundEvent>>,
Optional<AmbientMoodSettings>, List<AmbientAdditionsSettings>)`. Faithful vanilla downfall/
temperatureModifier read via `BiomeAccessor`(@Accessor climateSettings → Object) +
`ClimateSettingsAccessor`(string-targeted @Invoker) — works now that the compile-time AP is off.

## ✅ VanillaWorldProperties (dimension) overrides — RESTORED via EnvironmentAttributeMap
`DimensionUtil.createDimension` now builds an `EnvironmentAttributeMap` from the base dimension's
`attributes()` (`putAll`) and re-applies the configurable overrides on top, passing it as the
`attributes` ctor arg (mirrors `BiomeUtil.createBiome`): `respawnAnchorWorks`→`RESPAWN_ANCHOR_WORKS`,
`cloudHeight`→`CLOUD_HEIGHT` (`.floatValue()`), `bedWorks`→`BED_RULE`
(`CAN_SLEEP_WHEN_DARK`/`EXPLODES`), `ultraWarm`→`WATER_EVAPORATES`+`FAST_LAVA`+`INCREASED_FIRE_BURNOUT`.
Each applied only when the pack sets it (null = keep vanilla).
**Deliberately deferred** (carried from the base dimension unchanged, documented in-code): `fixedTime`
(moved to `timelines`/`defaultClock` — needs a `Timeline`/`WorldClock`, not an attribute), `natural`
(no clean 1:1 — would need to diff vanilla overworld vs nether attribute maps; candidates
`NETHER_PORTAL_SPAWNS_PIGLINS`/`CAN_START_RAID`), and `effects` (now `skybox` + `cardinalLightType`,
already inherited from whichever vanilla `vanilla-dimension` the pack bases on).

## ⚠ Build-config change made this session (important)
On **non-obfuscated** MC 26.1, loom **rejects** `mappings(loom.officialMojangMappings())`
("Cannot use Mojang mappings in a non-obfuscated environment"), and the legacy
sponge-mixin annotation processor **fatally errors on named `@Inject` targets**
("Unable to locate obfuscation mapping") because there is no obf map. Fix applied in
`platforms/mixin-common/build.gradle.kts`:
- `loom { mixin { useLegacyMixinAp.set(false) } }`
- removed the explicit `annotationProcessor("net.fabricmc:sponge-mixin:…")` (kept `compileOnly`).
This disables the **compile-time** mixin AP, so mixin `@At`/`@Inject`/`@Shadow`/`@Accessor`
targets are **no longer validated at compile** — they will only fail at **runtime** if wrong.
Loom still generates the refmap at remap time (identity, since dev names == runtime names).
**mixin-lifecycle and fabric will almost certainly need the same build change.**
Side effect that bit us: while the legacy AP was enabled it **silently masked real javac
errors** in whole files (DimensionUtil/PresetUtil/MinecraftAdapter showed 0 errors that were
actually ~38) — always disable it (or fix the @Inject) before trusting an error count.

## ⚠ 26.1 API overhauls that forced behavior reductions (revisit at runtime-verify)
- **`BiomeSpecialEffects`** slimmed to {waterColor, grass/foliage/dryFoliage overrides,
  grassColorModifier}. Fog/sky/water-fog colors, music, particles, ambient sounds became
  **`EnvironmentAttribute`s**. `BiomeUtil.createBiome` now copies vanilla attributes via
  `BiomeBuilder.putAttributes(vanilla.getAttributes())` and **drops config overrides** for
  fog/sky/music/particle/sounds (the `VanillaBiomeProperties` getters for those are now unused).
- **`Biome.ClimateSettings` is package-private** with no public `downfall()`/`temperatureModifier()`
  getter → `BiomeUtil` defaults downfall to `0.5f` and temp-modifier to `NONE` when config
  doesn't override (was: copied from vanilla). `BiomeAccessor`/`ClimateSettingsAccessor` were
  deleted (can't @Accessor a package-private-typed field).
- **`DimensionType`** record fully restructured (16 components; added skybox, cardinalLightType,
  EnvironmentAttributeMap, timelines, defaultClock; dropped fixedTime/ultrawarm/natural/bedWorks/
  respawnAnchorWorks/effects/cloudHeight). `DimensionUtil` rebuilds the `EnvironmentAttributeMap`
  and re-applies ultrawarm/bedWorks/respawnAnchorWorks/cloudHeight (see the ✅ dimension-overrides
  section above); fixedTime/natural/effects are deferred + documented in-code. `MonsterSettings`
  ctor is now `(IntProvider, int)` — dropped piglinSafe/hasRaids.
- **`StateHolder.PROPERTY_MAP_PRINTER` removed** → `StateAccessor` deleted; `BlockStateMixin.
  terra$getAsString` now uses `BlockStateParser.serialize(state)`.
- **yarn `BlockStateArgument` (result holder) = Mojang `BlockInput`** (NOT the `BlockStateArgument`
  ArgumentType). Migration had mis-mapped it everywhere; corrected across 6 files.
- **`IntProvider` is now an interface** (sample/minInclusive/maxInclusive/codec). `TerraIntProvider`
  `implements` it; registration via `Registry.register(BuiltInRegistries.INT_PROVIDER_TYPE,…)`
  returning `MapCodec<? extends IntProvider>`.
- Tick scheduling rewritten: `MinecraftUtil.schedulePhysics(state, pos, ScheduledTickAccess)` uses
  the `scheduleTick(pos, fluid/block, delay)` default (delay 0) instead of per-scheduler ticks.

### Original (pre-session) status, kept for reference
structural class/import migration applied; mixin-common had ~309 compile errors (per-site
API porting), now all resolved.

---

## TL;DR of where we are

1. **The dependency blocker is solved.** Bumping `cloud-fabric` `2.0.0-beta.15 → beta.16`
   (the first 26.1-supporting release, PR Incendo/cloud-minecraft-modded#123) clears the
   access-widener/namespace rejection. Proven empirically: `:platforms:fabric:build`
   now gets all the way through loom setup and fails only on Java source errors
   (yarn→Mojang), not on AW/namespace. fabric-api `0.149.1+26.1.2` is already the latest.
2. **MC 26.1 is unobfuscated** — the new `net.fabricmc.fabric-loom` 1.16.2 plugin builds
   against the Mojang-named jar directly (no remap, no yarn). Build system already staged
   (Gradle 9.5.1, loader 0.19.2). Only the **source** still speaks yarn.
3. **Structural migration done**: ~140 import/class FQNs migrated across 79 files in
   `mixin-common`, `mixin-lifecycle`, `fabric`. Name-collisions and semantic traps handled
   (see below). This is the ~40% mechanical layer.
4. **Remaining** (the ~60%, per-site): method-name renames, override-signature changes,
   34 mixin `@At`/`@Inject` descriptors, the `IntProviderType`→`MapCodec` rewrite, a few
   structural changes (sealed `Tag`, private fields), and the `terra.accesswidener` entries.
   Then it must actually *apply at runtime*.

## Ground-truth tooling (regenerate as needed)

- **Mojang-named MC jar** (the source of truth for all names):
  `C:/Users/Peter/.gradle/caches/fabric-loom/26.1.2/minecraft-merged.jar`
- **JDK 25 javap** (matches target): `C:/Program Files/Amazon Corretto/jdk25.0.3_9/bin/javap`
  - Usage: `javap -cp <jar> net.minecraft.world.level.ChunkPos` to get exact method/field
    signatures. This is how every method rename below was verified — **always verify, don't guess.**
- Regenerate the class index (used to verify every FQN):
  ```python
  import zipfile
  z=zipfile.ZipFile(r'C:/Users/Peter/.gradle/caches/fabric-loom/26.1.2/minecraft-merged.jar')
  names=sorted(n[:-6].replace('/','.') for n in z.namelist()
               if n.startswith('net/minecraft/') and n.endswith('.class') and '$' not in n)
  # 6596 classes; grep this for any simple name to find its Mojang package.
  ```
- Re-measure full error surface (javac caps display at 100 by default):
  ```
  echo "allprojects { tasks.withType(JavaCompile).configureEach { options.compilerArgs += ['-Xmaxerrs','2000'] } }" > /tmp/maxerr.gradle
  ./gradlew :platforms:mixin-common:compileJava -I /tmp/maxerr.gradle --console=plain
  ```

## Build state (already applied on this branch)

- `buildSrc/src/main/kotlin/Versions.kt`: `Fabric.cloud = "2.0.0-beta.16"`.
- `platforms/fabric/build.gradle.kts`: re-enabled (was `.disabled`); `cloud-fabric` +
  `fabric-api` deps uncommented.
- `platforms/{mixin-common,mixin-lifecycle}/build.gradle.kts`: re-enabled.
- NOTE: with these enabled, `./gradlew build` for the *other* platforms also won't compile
  until this is done. That's fine on this dedicated branch; do not merge to main until green.

---

## Verified class rename map (yarn FQN → Mojang FQN)

All targets below were confirmed present in the 26.1.2 jar. **Package-only moves** (simple
name unchanged) are not listed individually — e.g. `block.Block`→`world.level.block.Block`,
`registry.Registry`→`core.Registry`, `util.Identifier`→`resources.Identifier` (Identifier is
NOT renamed in 26.1), `sound.SoundEvent`→`sounds.SoundEvent`, `world.biome.Biome`→
`world.level.biome.Biome`, etc. The true *renames*:

| yarn | Mojang |
|---|---|
| block.AbstractBlock(.AbstractBlockState) | world.level.block.state.BlockBehaviour(.BlockStateBase) |
| block.BlockEntityProvider | world.level.block.EntityBlock |
| block.FluidBlock | world.level.block.LiquidBlock |
| block.entity.LockableContainerBlockEntity | world.level.block.entity.BaseContainerBlockEntity |
| block.entity.LootableContainerBlockEntity | world.level.block.entity.RandomizableContainerBlockEntity |
| block.entity.MobSpawnerBlockEntity | world.level.block.entity.SpawnerBlockEntity |
| block.pattern.CachedBlockPosition | world.level.block.state.pattern.BlockInWorld |
| block.spawner.MobSpawnerEntry | world.level.SpawnData |
| block.spawner.MobSpawnerLogic | world.level.BaseSpawner |
| command.CommandRegistryAccess | commands.CommandBuildContext |
| command.argument.BlockArgumentParser | commands.arguments.blocks.BlockStateParser |
| command.argument.ItemStackArgumentType | commands.arguments.item.ItemArgument |
| command.argument.ParticleEffectArgumentType | commands.arguments.ParticleArgument |
| component.ComponentChanges | core.component.DataComponentPatch |
| component.ComponentMap | core.component.DataComponentMap |
| component.MergedComponentMap | core.component.PatchedDataComponentMap |
| component.type.ItemEnchantmentsComponent | world.item.enchantment.ItemEnchantments |
| entity.SpawnGroup | world.entity.MobCategory |
| entity.SpawnReason | world.entity.EntitySpawnReason |
| entity.player.PlayerEntity | world.entity.player.Player |
| nbt.NbtCompound | nbt.CompoundTag |
| nbt.NbtElement | nbt.Tag *(now `sealed` — see structural notes)* |
| nbt.StringNbtReader | nbt.TagParser |
| registry.CombinedDynamicRegistries | core.LayeredRegistryAccess |
| registry.DynamicRegistryManager | core.RegistryAccess |
| registry.RegistryKey | resources.ResourceKey |
| **registry.Registries** | **core.registries.BuiltInRegistries** ⚠ trap |
| **registry.RegistryKeys** | **core.registries.Registries** ⚠ trap |
| registry.RegistryWrapper(.Impl)(.WrapperLookup) | core.HolderLookup(.RegistryLookup)(.Provider) |
| registry.MutableRegistry | core.WritableRegistry |
| registry.SimpleRegistry | core.MappedRegistry |
| registry.RegistryLoader | resources.RegistryDataLoader |
| registry.RegistryEntryLookup | core.HolderGetter |
| registry.ReloadableRegistries | server.ReloadableServerRegistries |
| registry.ServerDynamicRegistryType | server.RegistryLayer |
| registry.entry.RegistryEntry(.Reference) | core.Holder(.Reference) |
| registry.entry.RegistryEntryList | core.HolderSet |
| registry.tag.TagGroupLoader | tags.TagLoader |
| resource.featuretoggle.FeatureSet | world.flag.FeatureFlagSet |
| resource.ResourcePackManager | server.packs.repository.PackRepository |
| server.DataPackContents | server.ReloadableServerResources |
| server.command.CommandManager | commands.Commands |
| server.command.ServerCommandSource | commands.CommandSourceStack |
| server.network.ServerPlayerEntity | server.level.ServerPlayer |
| server.world.ServerWorld | server.level.ServerLevel |
| sound.BiomeAdditionsSound | world.attribute.AmbientAdditionsSettings |
| sound.BiomeMoodSound | world.attribute.AmbientMoodSettings |
| sound.MusicSound | sounds.Music |
| state.State | world.level.block.state.StateHolder |
| text.Text | network.chat.Component |
| util.math.intprovider.IntProvider | util.valueproviders.IntProvider |
| util.math.intprovider.**IntProviderType** | **gone — `IntProvider.codec()` returns `MapCodec`; needs rewrite** |
| util.math.noise.DoublePerlinNoiseSampler | world.level.levelgen.synth.NormalNoise |
| util.math.random.CheckedRandom | world.level.levelgen.LegacyRandomSource |
| util.math.random.ChunkRandom | world.level.levelgen.WorldgenRandom |
| util.math.random.Random | util.RandomSource |
| util.math.random.RandomSeed | world.level.levelgen.RandomSupport |
| util.math.random.RandomSequencesState | world.RandomSequences |
| util.collection.BoundedRegionArray | util.StaticCache2D&lt;GenerationChunkHolder&gt; *(generic; constructor type)* |
| world.ChunkRegion | server.level.WorldGenRegion |
| world.HeightLimitView | world.level.LevelHeightAccessor |
| world.MutableWorldProperties | world.level.storage.WritableLevelData |
| world.SpawnHelper | world.level.NaturalSpawner |
| world.StructureWorldAccess | world.level.WorldGenLevel |
| world.World | world.level.Level |
| world.WorldAccess | world.level.LevelAccessor |
| world.Heightmap(.Type) | world.level.levelgen.Heightmap(.Types) |
| world.biome.BiomeEffects(.GrassColorModifier) | world.level.biome.BiomeSpecialEffects(.GrassColorModifier) |
| world.biome.GenerationSettings | world.level.biome.BiomeGenerationSettings ⚠ collides w/ Terra's own `GenerationSettings` |
| world.biome.SpawnSettings(.SpawnEntry) | world.level.biome.MobSpawnSettings(.SpawnerData) |
| world.biome.source.BiomeAccess | world.level.biome.BiomeManager |
| world.biome.source.util.MultiNoiseUtil(.MultiNoiseSampler) | world.level.biome.Climate(.Sampler) |
| world.chunk.Chunk | world.level.chunk.ChunkAccess |
| world.chunk.ChunkGenerationStep | world.level.chunk.status.ChunkStep |
| world.chunk.ChunkSection | world.level.chunk.LevelChunkSection |
| world.chunk.WorldChunk | world.level.chunk.LevelChunk |
| world.chunk.PalettesFactory | world.level.chunk.PalettedContainerFactory |
| world.chunk.ChunkLoadProgress | **removed from ServerLevel ctor — descriptor change, no replacement type** |
| world.dimension.DimensionOptions | world.level.dimension.LevelStem |
| **world.dimension.DimensionTypes** | **world.level.dimension.BuiltinDimensionTypes** ⚠ trap (NOT data.worldgen.DimensionTypes) |
| world.gen.StructureAccessor | world.level.StructureManager |
| world.gen.StructureWeightSampler | world.level.levelgen.Beardifier |
| world.gen.chunk.ChunkGeneratorSettings | world.level.levelgen.NoiseGeneratorSettings |
| world.gen.chunk.NoiseChunkGenerator | world.level.levelgen.NoiseBasedChunkGenerator |
| world.gen.chunk.VerticalBlockSample | world.level.NoiseColumn |
| world.gen.densityfunction.DensityFunction(.UnblendedNoisePos) | world.level.levelgen.DensityFunction(.SinglePointContext) |
| world.gen.noise.NoiseConfig | world.level.levelgen.RandomState |
| world.level.ServerWorldProperties | world.level.storage.ServerLevelData |
| world.level.storage.LevelStorage | world.level.storage.LevelStorageSource |
| world.spawner.SpecialSpawner | world.level.CustomSpawner |
| world.tick.MultiTickScheduler | world.ticks.LevelTickAccess |
| world.tick.OrderedTick | world.ticks.ScheduledTick |
| world.tick.TickScheduler | world.ticks.TickAccess |
| world.tick.WorldTickScheduler | world.ticks.LevelTicks |

### ⚠ Semantic traps (already corrected in the applied migration — verify if re-running)
- yarn `Registries` (holds **registry instances**) = Mojang **`BuiltInRegistries`**, NOT
  Mojang `Registries` (which holds ResourceKeys = yarn `RegistryKeys`). Apply
  `Registries→BuiltInRegistries` **before** `RegistryKeys→Registries`.
- yarn `DimensionTypes` = `BuiltinDimensionTypes`, NOT `data.worldgen.DimensionTypes`.
- Lesson: a unique simple-name match in the jar is **necessary but not sufficient** —
  same-name classes can have different semantics. Verify registry/key/type holders by usage.

## Verified method renames (javap-confirmed)

| yarn call | Mojang | receiver |
|---|---|---|
| getBottomY() | getMinY() | LevelHeightAccessor |
| getTopYInclusive() | getMaxY() | LevelHeightAccessor |
| getStartX()/getStartZ() | getMinBlockX()/getMinBlockZ() | ChunkPos |
| getCenterPos() | getCenter() | WorldGenRegion |
| getDefaultState() | defaultBlockState() | Block |
| getMainWorkerExecutor() | backgroundExecutor() *(returns TracingExecutor)* | Util |
| createStructureWeightSampler(StructureManager,ChunkPos) | forStructuresInChunk(..) | Beardifier |
| populateEntities(WorldGenRegion,Holder&lt;Biome&gt;,ChunkPos,WorldgenRandom) | spawnMobsForChunkGeneration(ServerLevelAccessor,..) | NaturalSpawner |
| containsId(Identifier) | containsKey(Identifier) | Registry |
| spawnCost(EntityType,Double,Double) | addMobCharge(EntityType,double,double) | MobSpawnSettings.Builder |
| creatureSpawnProbability(Float) | creatureGenerationProbability(float) | MobSpawnSettings.Builder |
| generateFeatures(WorldGenLevel,ChunkAccess,StructureManager) | applyBiomeDecoration(..) | ChunkGenerator |
| getSeed() *(on RandomSupport)* | generateUniqueSeed() | RandomSupport (static) |

### Context-dependent — must be fixed per call site (NOT blanket renames)
- **`getEntry(...)`**: on a `Registry` with a *value* arg → `wrapAsHolder(value)` (returns
  `Holder`); with an *Identifier* arg → `get(id)` (returns `Optional<Holder.Reference>`).
  But `MinecraftUtil.getEntry(...)` is **Terra's own** method — leave it.
- **`getKey()`**: on a `Holder` → `unwrapKey()` (`Optional<ResourceKey>`); on a `Registry`
  → `key()` (the registry's own key).
- **`getSeed()`**: `SeedHack.getSeed(...)` is **Terra's own**; `RandomSupport.getSeed()` →
  `generateUniqueSeed()`; level/region `getSeed()` may still exist — javap to confirm.
- **`getBiomes()` / `getMultiNoiseSampler()`** on `TerraBiomeSource`: these are overrides;
  `BiomeSource`'s abstract is now `collectPossibleBiomes()` (protected) and
  `getNoiseBiome(int,int,int,Climate.Sampler)`. Re-derive Terra's overrides to match.

## Name collisions (Terra's own types vs Mojang)
- **`Player`**: Terra has `com.dfsek.terra.api.entity.Player`. After `PlayerEntity`→`Player`,
  `PlayerEntityMixin` collides → fixed by dropping the MC import and using FQN
  `@Mixin(net.minecraft.world.entity.player.Player.class)`. Watch for any other file using both.
- **`GenerationSettings`**: Terra has `com.dfsek.terra.mod.generation.GenerationSettings`.
  Only rename the bare token to `BiomeGenerationSettings` in files that import the MC class
  (`GenerationSettingsFloraFeaturesMixin`, `BiomeUtil`); leave Terra's own (`Codecs`,
  `MinecraftChunkGeneratorWrapper`, `PresetUtil`, `GenerationSettings.java`) alone.

## The 4 "hard" classes (resolved via javap on constructors)
- `PalettesFactory` → `PalettedContainerFactory` (ProtoChunk ctor param).
- `BoundedRegionArray` → `StaticCache2D<GenerationChunkHolder>` (WorldGenRegion ctor;
  `ChunkRegionMixin` constructor inject — type + descriptor change).
- `ChunkLoadProgress` → **removed** from `ServerLevel` ctor; the `ServerWorldMixin` /
  `MinecraftServerMixin` `<init>` descriptors must be rewritten to the new signature
  (see `javap ServerLevel`).
- `IntProviderType` → **gone**; 26.1 `IntProvider` exposes `MapCodec<? extends IntProvider>
  codec()`. `TerraIntProvider` + `MinecraftUtil.registerIntProviderTypes()` need a real
  rewrite to the codec-based registration. This is logic porting, not a rename.

## Remaining error surface (mixin-common, full count = 309)
- 240 `cannot find symbol` (method/field renames per above + per-site)
- 20 override-signature mismatches (e.g. `getNoiseBiome(..,Climate.Sampler)`,
  `addDebugScreenInfo(List<String>,RandomState,BlockPos)`, `enabledFeatures()` on CommandBuildContext)
- structural: `Tag` is now `sealed` (a class extends it — restructure), `TYPE_CODEC` private
  in `BlockEntity`, constructor sig changes (`BlockStateArgument`, `MonsterSettings`, `Builder`),
  `getString`/`put` on `CompoundTag` signature changes, `Property[]` vs map, etc.
- leftover inline FQNs that escaped (no bare import): `ServerCommandSourceMixin` has
  `net.minecraft.entity.@Nullable Entity` (annotation split the FQN) → `world.entity`.
- Then repeat for **mixin-lifecycle** (second module, not yet measured) and **fabric**.

## Remaining work — suggested order
1. Sweep the few leftover inline/annotation-split FQNs (`net.minecraft.entity.@Nullable Entity`, etc.).
2. Apply the verified method renames table (mostly mechanical), then re-measure.
3. Work the context-dependent calls (`getEntry`/`getKey`/`getSeed`) per site.
4. Fix override signatures against the new abstract methods (javap the supertypes).
5. ChunkPos `.x`/`.z` are private now → use accessors (`getMinBlockX` etc. or record components — javap).
6. Rewrite `IntProviderType`→codec registration in `TerraIntProvider`/`MinecraftUtil`.
7. Rewrite the 34 mixin `@At`/`@Inject` descriptor strings + the changed constructor
   descriptors (`ServerLevel`, `ProtoChunk`, `WorldGenRegion`). javap each target; the mixin
   AP validates these at compile time (they show as "no targets"/"cannot find method").
8. Migrate `platforms/mixin-common/src/main/resources/terra.accesswidener` entries
   (header already `official`; the entry *paths* still use yarn names — translate via the map above).
9. Repeat for mixin-lifecycle + fabric; build `:platforms:fabric:build` to green.
10. **Runtime check**: a green compile is necessary, not sufficient — mixins must actually
    apply. Launch and verify world-gen works.

## External dependency notes
- fabric-api `0.149.1+26.1.2` is the latest; nothing newer to chase.
- cloud-fabric `beta.16` is required (beta.15 lacks 26.1). cloud builds with a forked
  `quiet-fabric-loom`; that only affects how cloud builds, not consuming its published artifact.
- The IntelliJ "migration map" Fabric mentions automates yarn→Mojang *symbol* renames in
  regular Java, but NOT mixin `@At` descriptor strings or accesswidener entries (those are
  strings). The mapping tables above cover what the IDE map would do, plus the string layers.
