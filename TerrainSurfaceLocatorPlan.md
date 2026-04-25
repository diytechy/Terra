# Implementation Plan: TerrainSurfaceLocator and TopTerrainSurfaceLocator

Two new locators that find the terrain surface by querying the 3D noise density
function directly, rather than scanning placed blocks.  This avoids the
neighbor-chunk contamination problem where features placed by an adjacent
chunk's population stage (e.g. trees) create false air/solid transitions that
block-scanning locators (`SurfaceLocator`, `TopLocator`) can mistake for terrain
surface.

Density > 0 means solid terrain; ≤ 0 means air.  The surface is the transition
where `density(y) > 0` and `density(y+1) ≤ 0` going upward.

---

## New module: `common/addons/locator-surface-noise-3d/`

`settings.gradle.kts` auto-discovers any subdirectory of `common/addons/` that
contains a `build.gradle.kts`, so **no existing files need modification**.

Reference addon: `common/addons/locator-slant-noise-3d/` — same architecture,
same dependency chain.

---

## Files to create (7 total)

```
common/addons/locator-surface-noise-3d/
├── build.gradle.kts
└── src/main/
    ├── java/com/dfsek/terra/addon/feature/locator/surface/
    │   ├── TerrainSurfaceLocatorAddon.java
    │   ├── TerrainSurfaceLocator.java
    │   ├── TerrainSurfaceLocatorTemplate.java
    │   ├── TopTerrainSurfaceLocator.java
    │   └── TopTerrainSurfaceLocatorTemplate.java
    └── resources/
        └── terra.addon.yml
```

---

## File details

### `build.gradle.kts`

Mirrors `locator-slant-noise-3d/build.gradle.kts` exactly:

```kotlin
version = version("1.0.0")

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
    compileOnlyApi(project(":common:addons:chunk-generator-noise-3d"))
}
```

---

### `src/main/resources/terra.addon.yml`

```yaml
schema-version: 1
contributors:
  - Terra contributors
id: locator-surface-noise-3d
version: @VERSION@
entrypoints:
  - "com.dfsek.terra.addon.feature.locator.surface.TerrainSurfaceLocatorAddon"
website:
  issues: https://github.com/PolyhedralDev/Terra/issues
  source: https://github.com/PolyhedralDev/Terra
  docs: https://terra.polydev.org
license: MIT License
depends:
  chunk-generator-noise-3d: "[1.2.0,2.0.0)"
```

---

### `TerrainSurfaceLocatorAddon.java`

Entry point.  Identical structure to `SlantLocatorAddon`:

- Declares `LOCATOR_TOKEN` (`TypeKey<Supplier<ObjectTemplate<Locator>>>`)
- In `initialize()`, registers both types into the pack's locator registry:
  - `addon.key("TERRAIN_SURFACE")` → `TerrainSurfaceLocatorTemplate::new`
  - `addon.key("TOP_TERRAIN_SURFACE")` → `TopTerrainSurfaceLocatorTemplate::new`
- Uses `priority(1)` and `.failThrough()` on the event handler

---

### `TerrainSurfaceLocatorTemplate.java` and `TopTerrainSurfaceLocatorTemplate.java`

One field each, using the `@Default` pattern from `AdjacentPatternLocatorTemplate`:

```java
@Value("offset")
@Default
private @Meta int offset = 0;
```

Each `get()` constructs its respective locator, passing `offset`.

---

### `TerrainSurfaceLocator.java` — all solid-to-air transitions

Returns `true` at every y where the density function transitions from solid to
air going upward.  Multiple hits per column are possible (cave ceilings,
overhangs, main surface).

**Implementation:**

1. Cast `column.getWorld()` to `World`, then `world.getGenerator()` to
   `NoiseChunkGenerator3D`.
2. Obtain sampler:
   ```java
   Sampler3D sampler = generator.samplerProvider()
       .get(column.getX(), column.getZ(), world, world.getBiomeProvider());
   ```
   `SamplerProvider.get()` accepts world coordinates and converts to chunk
   coordinates internally — do not pre-divide by 16.
3. Compute chunk-relative coords required by `Sampler3D.sample()`:
   ```java
   int fdX = Math.floorMod(column.getX(), 16);
   int fdZ = Math.floorMod(column.getZ(), 16);
   ```
4. Use `BinaryColumnBuilder builder = column.newBinaryColumn()`.
5. Iterate `y` from `column.getMinY()` to `column.getMaxY() - 1` (inclusive).
6. Surface condition:
   ```java
   sampler.sample(fdX, y, fdZ) > 0
       && (y + 1 >= column.getMaxY() || sampler.sample(fdX, y + 1, fdZ) <= 0)
   ```
   The second clause treats the world ceiling as air (safe — noise is defined
   above `maxY` but no blocks are placed there).
7. On hit: compute `int target = y + offset`.  Skip if outside
   `[column.getMinY(), column.getMaxY())`.  Otherwise `builder.set(target)`.
8. Return `builder.build()`.

---

### `TopTerrainSurfaceLocator.java` — only the highest transition

Returns `true` at only the single highest solid-to-air transition (the main
terrain surface, ignoring cave ceilings below it).

**Implementation:**

Same sampler setup as above, then:

1. Iterate `y` from `column.getMaxY() - 1` **downward** to `column.getMinY()`.
2. On the first y that satisfies the surface condition:
   - Compute `int target = y + offset`.
   - If `target` is within `[column.getMinY(), column.getMaxY())`:
     return `new BinaryColumn(target, target + 1, yi -> true)`.
   - If `target` is out of bounds: return `BinaryColumn.getNull()`.
3. If the loop completes with no hit: return `BinaryColumn.getNull()`.

---

## YAML config format

```yaml
# All terrain surface transitions (cave ceilings, overhangs, main surface)
locator:
  type: locator-surface-noise-3d:TERRAIN_SURFACE
  offset: 0         # optional, default 0

# Only the topmost terrain surface — preferred for surface features
locator:
  type: locator-surface-noise-3d:TOP_TERRAIN_SURFACE
  offset: 0         # optional, default 0
```

A positive `offset` shifts the placement point above the detected surface.
A negative `offset` shifts it below (e.g. into the ground).

---

## Edge case handling

| Case | Handling |
|---|---|
| Sampling `y+1` at column top | Short-circuit: `y+1 >= maxY` counts as air, no out-of-bounds sampler call |
| `offset` pushes result outside column bounds | Multi: silently skip that hit. Top: return `BinaryColumn.getNull()` |
| No surface found in column | Multi: empty builder (all-false). Top: `BinaryColumn.getNull()` |
| Entirely air or entirely solid column | No transition detected; both locators return empty/null |
| Cave ceilings / overhangs | `TerrainSurfaceLocator` returns all of them by design. `TopTerrainSurfaceLocator` ignores them when the terrain surface is higher |

---

## Key source references

| File | Purpose |
|---|---|
| `common/addons/locator-slant-noise-3d/...SlantLocatorAddon.java` | Addon registration pattern to copy |
| `common/addons/locator-slant-noise-3d/...SlantLocator.java` | Generator cast + samplerProvider pattern to copy |
| `common/addons/locator-slant-noise-3d/...SlantLocatorTemplate.java` | Template pattern |
| `common/addons/config-locators/.../AdjacentPatternLocatorTemplate.java` | `@Default` field pattern for optional config values |
| `common/addons/chunk-generator-noise-3d/.../SamplerProvider.java` | `get(x, z, world, biomeProvider)` signature |
| `common/addons/chunk-generator-noise-3d/.../NoiseChunkGenerator3D.java` | `samplerProvider()` public accessor; `getSlant()` for fdX/fdZ pattern |
| `common/api/.../BinaryColumn.java` | `getNull()`, constructor for point column |
| `common/api/.../Column.java` | `getMinY()`, `getMaxY()`, `newBinaryColumn()` |
