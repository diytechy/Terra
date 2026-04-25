# Feature Placement: Distributors and Locators

Features in Terra are placed through a two-stage filter: a **distributor** decides *whether* to attempt placement at a given (x, z) column, and a **locator** decides *which y-coordinates* within that column are valid. A structure is generated only when both pass.

---

## The Pipeline

`FeatureGenerationStage` iterates over each (x, z) position in a chunk (at configurable `resolution` steps). For every position:

```
for each (x, z) in chunk:
    biome = getBiome(x, z)
    for each feature in biome:
        if feature.distributor.matches(x, z, seed):          // 2D gate
            heights = feature.locator.getSuitableCoordinates(column)  // 1D filter
            for each y where heights[y] == true:
                feature.structure.generate(x, y, z, ...)
```

Source: [FeatureGenerationStage.java:82-92](common/addons/generation-stage-feature/src/main/java/com/dfsek/terra/addons/generation/feature/FeatureGenerationStage.java#L82)

---

## Distributors

**Interface:** `Distributor` ([Distributor.java](common/api/src/main/java/com/dfsek/terra/api/structure/feature/Distributor.java))

```java
boolean matches(int x, int z, long seed);
```

A pure function — given a world position and seed, it returns `true` or `false`. No state is stored; the same inputs always produce the same output, which is what makes terrain generation deterministic.

### PaddedGridDistributor — the primary spacing mechanism

Source: [PaddedGridDistributor.java](common/addons/config-distributors/src/main/java/com/dfsek/terra/addons/feature/distributor/distributors/PaddedGridDistributor.java)

Config parameters: `width`, `padding`, `salt`

This is the key distributor for spaced-out features. It works by dividing the world into a grid of cells, each of size `cellWidth = width + padding`. Within each cell, a single point is selected pseudorandomly. The algorithm:

1. Compute which cell (x, z) falls in: `cellX = floor(x / cellWidth)`, `cellZ = floor(z / cellWidth)`
2. Compute local position within the cell: `localX = x - cellX * cellWidth`, `localZ = z - cellZ * cellWidth`
3. **Early reject**: if `localX >= width` or `localZ >= width`, return `false` immediately — this position is in the padding gap between cells
4. Hash the cell coordinates plus seed to pick a target point within the `width × width` region
5. Return `true` only if `localX == targetX && localZ == targetZ`

```
┌─────────────────────────────┐
│  width × width              │
│  (eligible area)    │padding│
│                     │       │
│         ★           │       │  cellWidth = width + padding
│                     │       │
│─────────────────────┼───────│
│ padding             │       │
└─────────────────────────────┘
```

The padding strip is a hard exclusion zone — no `matches()` call inside it can ever return `true`. This guarantees that feature placement attempts are separated by at least `padding` blocks horizontally. The `salt` parameter lets different feature types use independent random selections within the same grid structure.

### SamplerDistributor

Uses a noise sampler and a threshold. Returns `true` when `sampler.getSample(seed, x, z) < threshold`. Since noise samplers typically output in the range [-1, 1], a threshold of 0 passes roughly half the area. Useful for organic, irregular distributions (e.g., features that follow biome noise). Provides no separation guarantee on its own.

### PointSetDistributor

Returns `true` only for an explicit set of (x, z) coordinates. Useful for scripted or hand-placed features.

### Boolean combinators

`AndDistributor`, `OrDistributor`, `XorDistributor` combine two distributors with the corresponding boolean logic. For example, `AND(PaddedGrid, Sampler)` adds a noise-based probability check on top of the grid spacing.

---

## Locators

**Interface:** `Locator` ([Locator.java](common/api/src/main/java/com/dfsek/terra/api/structure/feature/Locator.java))

```java
BinaryColumn getSuitableCoordinates(Column<?> column);
```

The locator receives the full column (already clamped to the biome's valid y-range) and returns a `BinaryColumn` — a boolean array indexed by y. Every `true` entry in the result will trigger a structure generation attempt.

### BinaryColumn

Source: [BinaryColumn.java](common/api/src/main/java/com/dfsek/terra/api/structure/feature/BinaryColumn.java)

Stores which y-values are valid. Lazily evaluated: the backing `boolean[]` is computed on first iteration via `forEach()`. Supports `and()`, `or()`, and `xor()` operations to compose two columns:

- `and()`: narrowest intersection — valid only where both agree
- `or()`: union — valid where either agrees, spans the wider range
- `xor()`: valid where exactly one agrees

### Locator implementations

| Locator | What it returns |
|---|---|
| `SurfaceLocator` | Every y where the block is air and the block below is solid — all surface points in a height range |
| `TopLocator` | Only the single highest such surface point |
| `RandomLocator` | N randomly-chosen heights within a range, seeded per-column (`31*seed + x`, `31*seed + z`, `+ salt`) |
| `GaussianRandomLocator` | Same as Random, but heights drawn from a Gaussian distribution |
| `SamplerLocator` | Heights where a 2D sampler falls below a threshold |
| `Sampler3DLocator` | Every y where a 3D sampler `getSample(seed, x, y, z) > 0` — returns the entire solid interior of the terrain, not just the surface transition, so it will produce multiple hits per column wherever caves or overhangs exist |
| `PatternLocator` | Heights where the block at y matches a block pattern |
| `AdjacentPatternLocator` | Heights where the horizontally-adjacent blocks match a pattern (configurable: all 4, or at least 1) |

`SurfaceLocator` is the most common for surface vegetation — it handles overhangs, cliffs, and uneven terrain naturally because it scans the actual block data rather than assuming a flat surface.

---

## Spacing: What Is and Is Not Guaranteed

### What `PaddedGridDistributor` guarantees

- **Horizontal spacing between placement attempts**: at most one attempt per `cellWidth × cellWidth` cell; adjacent cells are separated by the padding strip. Two features from the same distributor will never be placed closer than `padding` blocks apart along either axis.
- **Determinism**: the same (x, z, seed) always gives the same result, so distributed generation across threads or sessions is consistent.

### What is NOT guaranteed

- **Overlap between different features or different distributors**: two features that each use their own `PaddedGridDistributor` can still place structures at positions that physically overlap, since each distributor operates independently.
- **Overlap between a distributor's placement attempt and vertical neighbors**: the locator may return multiple y-values for a single (x, z), and structures placed at nearby y-values may overlap vertically.
- **Structure bounding-box exclusion**: Terra does not reserve space or check bounding boxes after placement. The distributor/locator system ensures spaced *placement attempts*; actual non-intersection of structure geometry is not enforced. Pack authors are responsible for sizing `padding` to match the physical footprint of their structures.

### Achieving separation in practice

For features that must not intersect:
- Set `padding` ≥ the maximum radius of the structure in blocks
- Use `TopLocator` or `SurfaceLocator` rather than `RandomLocator` if vertical stacking is a concern
- Use `AND` with a `SamplerDistributor` to further thin out placements based on noise

---

## Configuration Example (conceptual)

```yaml
# A feature placed once per ~24×24 area, with 8 blocks of guaranteed spacing
distributor:
  type: PADDED_GRID
  width: 16      # random point chosen within 16×16 area
  padding: 8     # 8-block exclusion strip around each cell
  salt: 12345    # unique salt per feature type

locator:
  type: SURFACE  # place at every air-over-solid surface within the y-range
  search:
    min: 60
    max: 120
```

---

## Key Source Files

| File | Role |
|---|---|
| [Distributor.java](common/api/src/main/java/com/dfsek/terra/api/structure/feature/Distributor.java) | Interface |
| [Locator.java](common/api/src/main/java/com/dfsek/terra/api/structure/feature/Locator.java) | Interface |
| [BinaryColumn.java](common/api/src/main/java/com/dfsek/terra/api/structure/feature/BinaryColumn.java) | Vertical result type |
| [PaddedGridDistributor.java](common/addons/config-distributors/src/main/java/com/dfsek/terra/addons/feature/distributor/distributors/PaddedGridDistributor.java) | Primary spacing distributor |
| [SamplerDistributor.java](common/addons/config-distributors/src/main/java/com/dfsek/terra/addons/feature/distributor/distributors/SamplerDistributor.java) | Noise-based distributor |
| [SurfaceLocator.java](common/addons/config-locators/src/main/java/com/dfsek/terra/addons/feature/locator/locators/SurfaceLocator.java) | Surface scan locator |
| [RandomLocator.java](common/addons/config-locators/src/main/java/com/dfsek/terra/addons/feature/locator/locators/RandomLocator.java) | Random height locator |
| [FeatureGenerationStage.java](common/addons/generation-stage-feature/src/main/java/com/dfsek/terra/addons/generation/feature/FeatureGenerationStage.java) | Pipeline driver |
