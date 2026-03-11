# Terra Terrain Generation Pipeline
## Complete Reference: Samplers, Density, Block Placement, and Carving

> Consolidated from BiomeBlendConfig.txt, BlockPlacing.txt, and source code analysis.
> Last updated: 2025-03-10

---

## Table of Contents

1. [High-Level Generation Order](#1-high-level-generation-order)
2. [Sampler Architecture](#2-sampler-architecture)
3. [Density Evaluation (3D + 2D Combination)](#3-density-evaluation)
4. [Biome Blending](#4-biome-blending)
5. [Minimum Density Constraints](#5-minimum-density-constraints)
6. [Carving System](#6-carving-system)
7. [Block Placement from Density](#7-block-placement-from-density)
8. [Palette System](#8-palette-system)
9. [Slant Detection](#9-slant-detection)
10. [Pack Configuration Reference (ORIGEN2)](#10-pack-configuration-reference)
11. [Source File Index](#11-source-file-index)

---

## 1. High-Level Generation Order

The full generation pipeline for each chunk:

```
1. Terrain shaping     (noise-based density evaluation)
2. Carving             (caves, canyons — removes blocks)
3. Feature placement   (structures, vegetation, ores)
4. Biome-specific      (water levels, surface decoration — happens AFTER features)
```

Within step 1, per-chunk processing in `NoiseChunkGenerator3D.generateChunkData()`:

```
For each chunk column (x, z):
  1. Get biome column for that (x, z)
  2. For each y level (top to bottom):
     a. Sample 3D density = interpolated 3D noise + 2D elevation
     b. Apply min-density floor constraint (if configured)
     c. Sample carving
     d. If density > 0 AND not carved → place solid block from palette
     e. Else if y <= sea level → place water from ocean palette
     f. Else → air
```

---

## 2. Sampler Architecture

Each biome defines up to three samplers:

| Config Key | Dimensionality | Purpose |
|-----------|---------------|---------|
| `terrain.sampler` | 3D | Base terrain density — the main noise function |
| `terrain.sampler-2d` | 2D | Elevation profile — added to the 3D noise to shape the surface |
| `carving.sampler` | 3D | Cave/void generation — positive values = carved out |

These are configured in biome YAML under the `terrain:` and `carving:` keys, and are parsed by `BiomeNoiseConfigTemplate`.

### How 3D and 2D Samplers Combine

The `Sampler3D` class (used per-chunk, cached by `SamplerProvider`) combines:

```java
density = interpolator.getNoise(x, y, z) + elevationInterpolator.getElevation(x, z)
```

- **`interpolator`** = `ChunkInterpolator` — evaluates `terrain.sampler` (3D) on a coarse grid with trilinear interpolation, handles biome blending in 3D
- **`elevationInterpolator`** = `ElevationInterpolator` — evaluates `terrain.sampler-2d` (2D) at block resolution, handles biome blending in 2D

These two systems are **completely independent** — they blend separately with different algorithms and resolutions.

### Typical Sampler Pattern

In ORIGEN2, the 3D sampler is usually a simple height threshold:
```yaml
terrain:
  sampler:
    dimensions: 3
    type: EXPRESSION
    expression: -y + base    # positive below 'base', negative above
```

The 2D sampler provides all the interesting terrain variation:
```yaml
terrain:
  sampler-2d:
    dimensions: 2
    type: EXPRESSION
    expression: scale * herp(cellDistance(x, z), ...)
    samplers:
      elevationWarped: ...   # domain-warped elevation lookup
      cellMask: ...          # voronoi masking
```

The combination means: the 3D sampler sets a base density gradient (solid below Y=base, air above), and the 2D sampler shifts that surface up/down to create hills, valleys, mountains, etc.

---

## 3. Density Evaluation

### Interpolation Grid

The `ChunkInterpolator` does **not** evaluate the 3D sampler at every block. It samples on a coarse grid:

- Horizontal: every 4 blocks (**hardcoded** — not configurable)
- Vertical: every 4 blocks (**hardcoded** — not configurable)
- Between sample points: **trilinear interpolation**

This means the 3D terrain sampler is evaluated at roughly `5 × 5 × (height/4)` points per chunk, and all intermediate values are interpolated. The 4-block step is baked into `ChunkInterpolator` via bit-shifts (`<< 2`) and fixed array dimensions (`new Interpolator3[4][size][4]`).

> **Note:** The carving interpolator (`LazilyEvaluatedInterpolator`) has a *configurable* resolution via `carving.resolution.horizontal` (default 4) and `carving.resolution.vertical` (default 2) in pack.yml. The terrain `ChunkInterpolator` does not have this flexibility.

### Evaluation Points

Sample grid for a 16-block chunk width: positions 0, 4, 8, 12, 16 → 5 samples per axis.

The `ElevationInterpolator` operates at **block resolution** (every 1 block), sampling an 18×18 grid (positions -1 to 16 in x and z) to cover the chunk plus a 1-block border.

---

## 4. Biome Blending

Two independent blending systems handle transitions between biomes:

### 4a. 3D Terrain Blend (ChunkInterpolator)

**Config (pack.yml):**
```yaml
blend:
  terrain:
    defaults:
      distance: 3         # Blend radius in multiples of step
      step: 4             # Block spacing between samples
      weight: 1.0         # Weight in weighted average
    no-blend-tags:
      - subsurface         # Biomes with this tag get blendDistance=0
    y-range:
      min: -20             # Skip blend outside this Y range
      max: 270
```

**Per-biome override:**
```yaml
terrain:
  blend:
    distance: 3     # 0 = disabled (sharp edges)
    step: 4
    weight: 1.0
```

**Algorithm:**
1. At each coarse grid point (every 4 blocks), check all biomes within `distance × step` blocks
2. If all biomes are the same → use center sample directly (homogeneity optimization)
3. If mixed → compute weighted average:
   ```
   noise = Σ(sample_from_biome[i] * weight[i]) / Σ(weight[i])
   ```
4. Blending is skipped when `blendDistance = 0` or Y is outside `y-range`

**Effective radius:** `distance × step` blocks in each direction. E.g., distance=3, step=4 → 12-block radius.

**Important distinction — blend distance vs interpolation grid:**
- The **interpolation grid** (hardcoded 4-block spacing) controls *where* the full density computation is performed — at the 5×5 coarse grid points per chunk.
- The **blend distance + step** controls *what happens at each grid point*: how far the biome averaging kernel reaches. With `distance: 3, step: 4`, each grid point samples a 7×7 neighborhood of biome columns (reaching 12 blocks in each direction) and averages their noise.
- Reducing `distance` from 3 to 2 shrinks the kernel from 12 to 8 blocks — biome transitions become sharper/narrower. `distance: 1` gives a small 3×3 kernel (4 blocks). `distance: 0` skips blending entirely.
- The blend kernel radius is independent of the interpolation grid spacing. A smaller `distance` is meaningful even though the grid is coarse — it controls how many neighboring biomes influence each grid point's density value, not the sampling frequency of the result.

**Performance:** Larger `blendDistance` increases biome column lookups per chunk. The interpolator pre-scans for the max blend distance in each chunk and only allocates for the actual radius needed.

### 4b. 2D Elevation Blend (ElevationInterpolator)

**Config (pack.yml):**
```yaml
blend:
  terrain:
    elevation: 2       # Smoothing radius (kernel = (2×elevation+1)²)
```

**Per-biome:**
```yaml
terrain:
  blend:
    weight-2d: 1.0     # Weight in elevation blend average
```

**Algorithm:**
- Samples a `(2 × smooth + 1)²` neighborhood for every block position
- With `elevation=4` → 9×9 kernel of biome lookups per block (324 positions for the 18×18 grid)
- Uses `getBaseBiome()` — a **2D surface-level** biome lookup, NOT a 3D column scan
- Does NOT account for vertically-stacked biomes (caves below surface)
- Has the same homogeneity optimization — skips averaging when all neighbors are the same biome

**Key difference from 3D blend:**

| Property | 3D Blend (ChunkInterpolator) | 2D Blend (ElevationInterpolator) |
|----------|------------------------------|----------------------------------|
| Sampler used | `terrain.sampler` (3D) | `terrain.sampler-2d` (2D) |
| Grid resolution | Coarse (4-block steps) | Block resolution (1-block steps) |
| Radius config | `blend.terrain.defaults.distance × step` | `blend.terrain.elevation` |
| Biome lookup | Full 3D column | 2D surface only (`getBaseBiome()`) |
| Vertical awareness | Yes (stacked biomes) | No (surface biome only) |

**Practical implication:** If biomes differ only in base height, a large elevation blend radius can smooth transitions without 3D blending. But for packs with vertically-stacked biomes (e.g., caves under surface biomes), the 2D elevation blend cannot substitute for 3D blending.

---

## 5. Minimum Density Constraints

An optional system that enforces a density floor, preventing terrain from being eroded below a threshold.

**Config:**
```yaml
terrain:
  min-density:
    sampler: [3D sampler defining the floor]
    smooth: true/false      # Smooth or hard transition
    smooth-k: 0.1           # Smoothness parameter (lower = sharper)
    skip-tags: [biome tags to exclude]
```

**Evaluation:**
```java
if (minDensitySampler != null) {
    double floor = minDensitySampler.getSample(seed, cx, y, cz);
    if (minDensitySmooth) {
        density = smoothMax(density, floor, k);  // smooth exponential blend
    } else {
        density = Math.max(density, floor);       // hard floor
    }
}
```

Applied after the combined 3D+2D density is computed, before carving is checked. Unlike the terrain sampler (which is trilinearly interpolated on the coarse 4-block grid), the min-density sampler is evaluated at **every block position** for full per-block precision.

---

## 6. Carving System

Carving determines which blocks are removed to create caves and voids.

### Carving Interpolator

Uses `LazilyEvaluatedInterpolator` — similar to the terrain interpolator but:
- **Horizontal resolution:** default 4 blocks (configurable via `carving.resolution.horizontal`)
- **Vertical resolution:** default 2 blocks (configurable via `carving.resolution.vertical`)
- **Lazy evaluation:** Only computes carving samples when actually queried (not pre-computed for the whole chunk)
- Uses **trilinear interpolation** between sample points

### Carving Evaluation

```java
carvingSample = biome.carving.getSample(seed, x, y, z);
if (carvingSample > 0) {
    // Block is carved — becomes air (or water if below sea level)
}
```

### Carving vs Density

Carving is evaluated **after** density. The logic is:
```
if (density > 0) {
    if (carvingSample <= 0) {
        // Solid block: place from palette
    } else {
        // Would be solid, but carved out
        // Either reset palette depth or continue counting (configurable)
    }
}
```

The `updatePaletteWhenCarving` flag controls whether palette depth resets when a block is carved:
- `true`: palette depth resets to 0 at carved blocks (carved voids interrupt layer counting)
- `false`: palette depth continues incrementing through carved blocks

### ORIGEN2 Carving Configuration

ORIGEN2 defines a comprehensive carving template with multiple cave types blended together:

```yaml
# biomes/abstract/carving/carving_sampler_template.yml
carving:
  sampler:
    type: EXPRESSION
    expression: >
      max(spaghetti, noodle, cheese) - pillars
```

Cave types:
- **Spaghetti caves:** Two ridged 3D noise functions summed — creates narrow winding tunnels
- **Noodle caves:** Layered ridged noise with blending — creates slightly wider passages
- **Cheese caves:** Simplex 3D noise — creates large open chambers
- **Mega caves:** Very large cave systems at depth
- **Pillars:** Cellular noise subtracted from carving — creates support columns inside caves

Key variables:
```yaml
carvingThreshold: 0.55       # Higher = less carving overall
cheeseStrength: 0.7          # Higher = larger cheese caves
spaghettiAStrength: 0.58     # Spaghetti tunnel size
noodleAStrength: 0.605       # Noodle tunnel size
pillarStrength: 0.5          # Pillar frequency
carvingMinHeight: -63        # Y level where carving stops
cheeseSurfaceBreak: -20      # Distance from surface where cheese caves stop
```

Biomes apply this template via `extends` or direct reference:
```yaml
carving:
  sampler:
    type: EXPRESSION
    expression: $biomes/abstract/carving/carving_sampler_template.yml:expression
    samplers: $biomes/abstract/carving/carving_sampler_template.yml:samplers
    variables:
      "<<": [carving_sampler_template.yml:variables]
      carvingMaxMode: 1  # 1 = land heightmap, 0 = ocean
```

---

## 7. Block Placement from Density

The core block placement logic in `NoiseChunkGenerator3D` (simplified):

```java
for each (x, z) in chunk:
    paletteLevel = 0;
    for y from maxHeight down to minHeight:
        double density = sampler.sample(x, y, z);

        // Apply min-density floor
        if (minDensitySampler != null) {
            density = applyFloor(density, floor);
        }

        if (density > 0) {
            // SOLID BLOCK
            if (carver.sample(x, y, z) <= 0) {
                // Not carved: get block from palette
                block = palette.get(paletteLevel, x, y, z, seed);
                chunk.setBlock(x, y, z, block);
                paletteLevel++;
            } else if (updatePaletteWhenCarving) {
                paletteLevel = 0;  // reset depth at cave
            } else {
                paletteLevel++;    // count through cave
            }
        } else if (y <= seaLevel) {
            // WATER: below sea level and density <= 0
            block = seaPalette.get(seaLevel - y, x, y, z, seed);
            paletteLevel = 0;
        } else {
            // AIR: above sea level and density <= 0
            paletteLevel = 0;
        }
```

Key observations:
- **Density > 0 = solid.** The threshold is always 0 — not configurable.
- **paletteLevel** counts how many consecutive solid blocks deep we are from the last air/water. This drives palette layer selection (grass at depth 0, dirt at 1-3, stone at 4+, etc.)
- Iteration is **top-to-bottom**, so the first solid block encountered is the surface.

---

## 8. Palette System

Palettes map depth (layer count from surface) to block materials.

### PaletteHolder

`PaletteHolder` maps Y-height ranges to `Palette` objects. For a given Y coordinate, it returns the appropriate palette.

**Biome config:**
```yaml
palette:
  - GRASS: 255          # Use GRASS palette from Y=255 down
  - DIRT: 64            # Use DIRT palette from Y=64 down
  - DEEPSLATE: 0        # Use DEEPSLATE palette from Y=0 down
```

### Palette

Each `Palette` (implemented in `PaletteImpl`) returns a block based on:
- **Layer depth** (paletteLevel — how many solid blocks deep)
- **Coordinates** (x, y, z) — for noise-based variation
- **Seed** — for deterministic randomization

Example palette definition:
```yaml
layers:
  - materials:
      - "minecraft:grass_block": 1
    layers: 1                        # 1 block of grass
  - materials:
      - "minecraft:dirt": 1
    layers: 3                        # 3 blocks of dirt
  - materials:
      - "minecraft:stone": 1
    layers: 999                      # stone for everything below
```

### Ocean Palette

When `density <= 0` and `y <= seaLevel`, blocks come from the **ocean/sea palette** instead. The depth index is `seaLevel - y` (deeper water = higher index).

---

## 9. Slant Detection

Slant measures surface steepness to select alternative palettes on slopes (cliffs, steep hillsides).

**Config:**
```yaml
slant:
  - threshold: 0.4              # Steepness threshold
    palette:
      - STONE: 255              # Use stone palette on steep slopes
```

**Calculation methods** (in `SlantCalculationMethod`):

1. **Derivative:** Computes gradient magnitude of the density field
   ```
   slant = sqrt((ddensity/dx)² + (ddensity/dz)² + (ddensity/dy)²)
   ```

2. **DotProduct:** Approximates surface normal and dots with up vector
   ```
   slant = normal · (0, 1, 0)
   ```

If the computed slant exceeds a threshold, the slant palette is used instead of the normal palette for that block.

---

## 10. Pack Configuration Reference (ORIGEN2)

### Pack-Level Sampler Hierarchy

```
ORIGEN2/
├── pack.yml                          # Pack manifest + blend config
├── customization.yml                 # Global tuning parameters
├── math/
│   ├── samplers/                     # Pack-level sampler definitions
│   │   ├── elevation.yml             # Elevation (mountains, hills, plains)
│   │   ├── continents.yml            # Continental distribution
│   │   ├── rivers.yml                # River carving
│   │   └── ...
│   └── functions/                    # Reusable math functions (lerp, herp, etc.)
├── biomes/
│   └── abstract/
│       ├── terrain/                  # Abstract terrain samplers
│       │   ├── land/                 # EQ_LAND, EQ_ROCKY, EQ_COLUMNS, etc.
│       │   ├── aquatic/              # Ocean terrain
│       │   └── spot/                 # Special biomes (volcanoes)
│       ├── carving/                  # Cave carving templates
│       └── palettes/                 # Block palette templates
```

### Sampler Resolution from Pack to Biome

1. **Pack-level samplers** (in `math/samplers/`) define global noise functions (elevation, continents, rivers)
2. **Abstract terrain biomes** (in `biomes/abstract/terrain/`) define `terrain.sampler` and `terrain.sampler-2d` using pack-level samplers as building blocks
3. **Concrete biomes** extend abstract terrains via `extends:` and add palettes, features, etc.

Example chain:
```
concrete biome (temperate_grassland.yml)
  extends → EQ_LAND (abstract terrain)
    defines → terrain.sampler: -y + base
    defines → terrain.sampler-2d: scale * elevation(x, z)
      references → elevation (pack-level sampler from math/samplers/elevation.yml)
  extends → CARVING_LAND (abstract carving)
    defines → carving.sampler: $carving_sampler_template.yml
  extends → PALETTE_LAND (abstract palette)
    defines → palette layers
```

### Key Customization Variables

From `customization.yml`:
```yaml
terrain-base-y-level: 65       # Base terrain height
terrain-height: 180             # Maximum terrain height above base
terrain-ocean-base-y-level: 60  # Sea level
terrain-ocean-depth: 100        # Ocean floor depth
elevation-scale: 1.05           # Elevation noise map scale
elevation-fuzz: 0.25            # Domain warp amplitude for small-scale variation
elevation-factor-continental: 1  # Enables continental blending
elevation-factor-rivers: 1      # Enables river influence on elevation
```

### Common Sampler Types Used

| Type | Description |
|------|-------------|
| `EXPRESSION` | Custom math expression combining other samplers |
| `FBM` | Fractional Brownian Motion (fractal noise) |
| `OPEN_SIMPLEX_2` | Base simplex noise |
| `PERLIN` | Perlin noise |
| `RIDGED` | Ridged noise (inverted FBM) |
| `CELLULAR` | Cellular/Voronoi noise |
| `DOMAIN_WARP` | Warps input coordinates of another sampler |
| `CACHE` | Caches sampler output for performance |
| `LINEAR` | Linear scaling/transformation |
| `PSEUDOEROSION` | Simulated hydraulic erosion noise |

---

## 11. Source File Index

All paths relative to `c:\Projects\Terra\`.

### Core Generation

| File | Purpose |
|------|---------|
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/NoiseChunkGenerator3D.java` | Main chunk generation entry point — density evaluation, carving check, block placement loop |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/math/samplers/Sampler3D.java` | Combines ChunkInterpolator (3D) + ElevationInterpolator (2D) into final density |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/math/samplers/SamplerProvider.java` | Caffeine cache for per-chunk Sampler3D instances |

### Interpolation & Blending

| File | Purpose |
|------|---------|
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/math/interpolation/ChunkInterpolator.java` | 3D noise interpolation + biome blending (coarse grid, trilinear) |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/math/interpolation/ElevationInterpolator.java` | 2D elevation interpolation + biome blending (block resolution) |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/math/interpolation/LazilyEvaluatedInterpolator.java` | Carving interpolator (lazy, lower resolution) |

### Configuration

| File | Purpose |
|------|---------|
| `common/addons/chunk-generator-noise-3d/src/main/java/.../config/noise/BiomeNoiseConfigTemplate.java` | Parses biome-level terrain/carving config from YAML |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../config/noise/BiomeNoiseProperties.java` | Record holding parsed biome noise properties (base, elevation, carving samplers) |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../config/NoiseChunkGeneratorPackConfigTemplate.java` | Parses pack-level blend/generation config |

### Palettes & Slant

| File | Purpose |
|------|---------|
| `common/addons/chunk-generator-noise-3d/src/main/java/.../palette/PaletteHolder.java` | Maps Y-ranges to palette objects |
| `common/addons/config-palette/src/main/java/.../palette/PaletteImpl.java` | Palette implementation — layer depth to block material |
| `common/addons/chunk-generator-noise-3d/src/main/java/.../generation/math/SlantCalculationMethod.java` | Surface slope calculation for slant palettes |

### Biome Provider

| File | Purpose |
|------|---------|
| `common/api/src/main/java/.../world/biome/generation/BiomeProvider.java` | API interface for biome lookups |
| `common/addons/biome-provider-pipeline/src/main/java/.../biome/pipeline/PipelineBiomeProvider.java` | Pipeline-based biome provider implementation |

---

## Summary: The Complete Density-to-Block Pipeline

```
                    ┌─────────────────────────┐
                    │    BiomeProvider         │
                    │  (determines biome at    │
                    │   each x, y, z)          │
                    └──────────┬──────────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                 ▼
    ┌─────────────────┐ ┌──────────────┐ ┌──────────────┐
    │ terrain.sampler  │ │ terrain.     │ │ carving.     │
    │ (3D noise)       │ │ sampler-2d   │ │ sampler      │
    │                  │ │ (2D elev.)   │ │ (3D caves)   │
    └────────┬────────┘ └──────┬───────┘ └──────┬───────┘
             │                 │                 │
    ┌────────▼────────┐ ┌──────▼───────┐        │
    │ ChunkInterp.    │ │ ElevationInt.│        │
    │ (trilinear +    │ │ (block-res + │        │
    │  3D biome blend)│ │  2D blend)   │        │
    └────────┬────────┘ └──────┬───────┘        │
             │                 │                 │
             └────────┬────────┘                 │
                      ▼                          │
              ┌───────────────┐                  │
              │  Sampler3D    │                  │
              │  density =    │                  │
              │  3D + 2D      │                  │
              └───────┬───────┘                  │
                      │                          │
              ┌───────▼───────┐                  │
              │ min-density   │                  │
              │ floor         │                  │
              └───────┬───────┘                  │
                      │                          │
                      ▼                          ▼
              ┌─────────────────────────────────────┐
              │         Block Placement              │
              │                                      │
              │  if density > 0:                     │
              │    if carving <= 0:                   │
              │      → solid block (from palette)    │
              │    else:                              │
              │      → air (carved)                  │
              │  else if y <= seaLevel:              │
              │    → water (from ocean palette)      │
              │  else:                                │
              │    → air                             │
              └─────────────────────────────────────┘
```
