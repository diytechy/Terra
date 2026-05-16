# Paper 26.1 Migration Plan

## Context

Minecraft 26.1 ("Tiny Takeover") is the release version of what was previously known as
`1.21.11`. Mojang adopted a new **year.drop.hotfix** versioning scheme (26.1 = 2026, drop 1).
Terra's `PaperMC_26.1` branch already targets `1.21.11-rc3`, so most NMS code should be
compatible -- the primary work is updating version strings, build tooling, and verifying
NMS reflection targets.

Paper 26.1 also requires **Java 25** (up from 21).

---

## Phase 1: Build Configuration Updates

These are mechanical version bumps. All changes are in `buildSrc/` and build scripts.

### 1.1 - `Versions.kt` (Bukkit block)

File: `buildSrc/src/main/kotlin/Versions.kt`, lines 63-75

| Constant | Current | New |
|----------|---------|-----|
| `minecraft` | `"1.21.11-rc3"` | `"26.1"` |
| `nms` | `"$minecraft-R0.1"` | `"$minecraft-R0.1"` (unchanged formula, resolves to `26.1-R0.1`) |
| `paperBuild` | `"$nms-20251208.200020-2"` | Needs the correct 26.1 SNAPSHOT timestamp from Paper's Maven repo (`https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/`). Look for the latest `26.1-R0.1-SNAPSHOT` build. |
| `paperWeight` | `"2.0.0-beta.18"` | Check if a newer beta is needed for 26.1 support (current beta.18 may work, but Paper's own build uses a specific version -- verify against their `build.gradle.kts`). |

### 1.2 - `Versions.kt` (Mod block, if building Fabric too)

File: `buildSrc/src/main/kotlin/Versions.kt`, line 49

| Constant | Current | New |
|----------|---------|-----|
| `Mod.minecraft` | `"1.21.11"` | `"26.1"` |
| `Mod.yarn` | `"$minecraft+build.3"` | Needs matching Fabric yarn build for 26.1 |
| `Mod.fabricLoader` | `"0.18.3"` | Verify compatibility with 26.1 |
| `Mod.fabricAPI` (in Fabric block) | `"0.140.0+${Mod.minecraft}"` | Needs 26.1 Fabric API version |

> **Note**: If this branch is Paper-only, the Mod/Fabric versions can be deferred.

### 1.3 - `buildSrc/build.gradle.kts`

File: `buildSrc/build.gradle.kts`, line 27

```kotlin
implementation("io.papermc.paperweight.userdev", "...", "2.0.0-beta.18")
```

Must match the `paperWeight` version in `Versions.kt`. Update if a newer paperweight beta is
required for 26.1.

### 1.4 - Java Toolchain (21 -> 25)

Two locations:

1. **`build.gradle.kts` (root)**, around line 17:
   ```kotlin
   options.release.set(21)  // -> 25
   ```

2. **`buildSrc/src/main/kotlin/CompilationConfig.kt`**, lines 33-34:
   ```kotlin
   sourceCompatibility = JavaVersion.VERSION_21  // -> VERSION_25
   targetCompatibility = JavaVersion.VERSION_21  // -> VERSION_25
   ```

> **Prerequisite**: JDK 25 must be installed locally. If JDK 25 isn't available yet (it's
> an EA/early-access release as of March 2026), you may be able to use JDK 24 with
> `--enable-preview` or wait for the GA. Verify what Paper's CI actually builds with.

### 1.5 - `plugin.yml`

File: `platforms/bukkit/common/src/main/resources/plugin.yml`, line 8

```yaml
api-version: "1.21.1"  # -> "26.1"
```

---

## Phase 2: Version Detection Code

### 2.1 - `NMSInitializer.java`

File: `platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/NMSInitializer.java`, line 12

```java
List<String> SUPPORTED_VERSIONS = List.of("v1.21.11");  // -> List.of("v26.1")
```

The `VersionUtil.MinecraftVersionInfo` regex `(\d+)\.(\d+)(?:\.(\d+))?` will correctly parse
`"26.1"` as `major=26, minor=1, patch=-1`, producing `toString()` = `"v26.1"`. No regex
changes needed.

---

## Phase 3: NMS Compatibility Verification

These are the areas that need **compile testing and runtime verification**. Based on research,
most should work without changes, but each must be confirmed.

### 3.1 - Reflection Proxies (LOW RISK)

File: `platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/Reflection.java`

All proxied fields/methods appear to still exist in 26.1:

| Proxy | Target | Status |
|-------|--------|--------|
| `MappedRegistryProxy.getByKey` | `MappedRegistry.byKey` | Present |
| `MappedRegistryProxy.setAllTags` | `MappedRegistry.allTags` | Present |
| `MappedRegistryProxy.setFrozen` | `MappedRegistry.frozen` | Present |
| `MappedRegistryProxy.invokeCreateTag` | `MappedRegistry.createTag` | Present |
| `MappedRegistryTagSetProxy.invokeFromMap` | `MappedRegistry$TagSet.fromMap` | Present |
| `StructureManagerProxy.getLevel` | `StructureManager.level` | Present |
| `ReferenceProxy.invokeBindValue` | `Holder.Reference.bindValue` | Present |
| `ReferenceProxy.invokeBindTags` | `Holder.Reference.bindTags` | Present |
| `ChunkMapProxy.getWorldGenContext` | `ChunkMap.worldGenContext` | Present |
| `HolderSetNamedProxy.invokeBind` | `HolderSet.Named.bind` | Present |
| `HolderSetNamedProxy.invokeContents` | `HolderSet.Named.contents` | Present |
| `BiomeProxy.invokeGrassColorFromTexture` | `Biome.getGrassColorFromTexture` | **Uncertain** -- may be removed in EnvironmentAttributes migration. However this appears to be dead code (nothing calls it). |
| `VillagerTypeProxy.getByBiome` | `VillagerType.BY_BIOME` | Likely present |

**Action**: Compile against the 26.1 dev bundle. If `BiomeProxy` fails, remove it (dead code).

### 3.2 - Tag System (MEDIUM RISK)

File: `platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/AwfulBukkitHacks.java`

Paper's "finish TagKey -> HolderSet migration" commit may affect how `registry.getTags()`
returns data and how `HolderSet.Named` objects are constructed. The `bindTags` and `resetTags`
methods manipulate tags at a very low level.

**Action**: This is the highest-risk area. After updating versions, attempt to compile and then
test with a world that has custom Terra biomes. Verify that:
- Biome registration succeeds without exceptions
- Tag inheritance from vanilla biomes to Terra biomes works
- Biome tags are queryable at runtime

### 3.3 - ChunkGenerator Delegate (LOW RISK)

File: `platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/NMSChunkGeneratorDelegate.java`

Method signatures for `fillFromNoise`, `applyCarvers`, `buildSurface`, `applyBiomeDecoration`
appear unchanged. `Beardifier.forStructuresInChunk` is still present.

One thing to watch: if `getMobsAt` return type changed from `WeightedRandomList` to
`WeightedList`, the class won't compile if it inherits that method. Terra doesn't override it
so it should be fine, but verify at compile time.

**Action**: Compile and verify. Likely no changes needed.

### 3.4 - Biome Injector (LOW RISK)

File: `platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/NMSBiomeInjector.java`

Already partially migrated (many properties commented out with `TODO: Migrate to
EnvironmentAttributes`). The remaining active code (`waterColor`, `grassColorModifier`,
`grassColorOverride`, `foliageColorOverride`, `hasPrecipitation`, `temperature`, `downfall`,
`temperatureAdjustment`, `mobSpawnSettings`) should still compile.

**Action**: Compile and verify. The EnvironmentAttributes migration is a separate enhancement,
not a blocker.

### 3.5 - BiomeSource / NMSBiomeProvider (LOW RISK)

`BiomeSource.getNoiseBiome` and `collectPossibleBiomes` signatures appear unchanged.

**Action**: Compile and verify.

---

## Phase 4: Compile, Test, Fix

### 4.1 - Initial Compilation

1. Update all version strings per Phases 1-2
2. Run `./gradlew :platforms:bukkit:nms:compileJava` to check NMS module
3. Run `./gradlew :platforms:bukkit:common:compileJava` to check common module
4. Run full `./gradlew build` for complete project

### 4.2 - Fix Compilation Errors

Address any errors found. Most likely candidates:
- `BiomeProxy.getGrassColorFromTexture` if method was removed (fix: delete proxy)
- `WeightedRandomList` -> `WeightedList` rename if it leaks into any overridden method
- Any field/method renames from Paper's "bunch of renames" commit

### 4.3 - Runtime Testing

1. `./gradlew :platforms:bukkit:runServer` (requires JDK 25)
2. Create a Terra world and verify:
   - World generates without errors
   - Custom biomes appear correctly
   - Biome tags work (test with biome-dependent features)
   - Structure blending (bearding) works
   - Multiverse integration (if applicable)

---

## Phase 5: Optional Enhancements (Not Required for Basic Port)

### 5.1 - EnvironmentAttributes Migration

Complete the `TODO` items in `NMSBiomeInjector.java` to use the new
`EnvironmentAttributes` system for fog color, sky color, water fog color, etc.
This would restore full custom biome visual property support.

### 5.2 - Block Tag Updates

Mojang split `#dirt` into `#dirt`, `#mud`, `#moss_blocks`, `#grass_blocks`, and
`#substrate_overworld`. Review Terra config packs (especially ORIGEN2) to ensure
tag references are still correct.

### 5.3 - Level Storage Changes

26.1 reorganized level storage layout. Test that multi-world setups (Multiverse)
still work correctly. This is largely handled by Paper/CraftBukkit, but verify.

---

## Dependency Resolution Checklist

Before starting, resolve these Maven coordinates:

- [ ] Paper dev bundle for 26.1: check `https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/` for `26.1-R0.1-SNAPSHOT` builds
- [ ] Paperweight plugin version: verify `2.0.0-beta.18` works with 26.1, or find newer version
- [ ] JDK 25 availability: ensure it's installed or determine if JDK 24 + preview flags suffice
- [ ] (If Fabric too) Fabric API / Yarn / Loader versions for 26.1

---

## Risk Summary

| Area | Risk | Mitigation |
|------|------|------------|
| Version strings | None | Mechanical change |
| Java 25 | Low | JDK availability only concern |
| Reflection proxies | Low | All targets verified present in 26.1 |
| Tag system (AwfulBukkitHacks) | Medium | Paper's TagKey->HolderSet migration could cause subtle breakage; test thoroughly |
| ChunkGenerator signatures | Low | Appear unchanged |
| Biome injection | Low | Active code paths appear stable |
| EnvironmentAttributes | N/A | Already broken/commented out pre-26.1; not a regression |
