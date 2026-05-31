# Plan 2: Pack-Level Terrain Samplers in ORIGEN2

## Overview

Inline 2D sub-samplers defined inside biome-specific `terrain.sampler` expressions are not currently pack-level, so they do not receive the automatic `LastValueSampler` wrapping that pack-level 2D samplers get. Moving them to pack-level makes them shared singleton instances, which: (1) auto-wraps them in `LastValueSampler`; (2) allows explicit `type: CACHE` wrapping when the single-slot cache is insufficient; (3) enables reuse across multiple biome types where the same sampler logic is duplicated.

This plan is complementary to Plan 1 (column cache): Plan 1 eliminates same-biome redundancy within the blend loop; Plan 2 eliminates 2D sub-sampler redundancy across y-levels and across different-biome calls to shared sub-expressions.

---

## Audit of Inline 2D Sub-Samplers by Terrain Type

| Terrain file | Inline 2D sampler name | Type | Shared with other terrain? |
|---|---|---|---|
| eq_rocky | `elevationWarped` | DOMAIN_WARP of elevation | No |
| eq_rocky | `cellMask` | PROBABILITY of OPEN_SIMPLEX_2 | No |
| eq_rocky | `cellLookup` | CELLULAR NoiseLookup of elevation | No |
| eq_rocky | `cellDistance` | FBM of CELLULAR | No |
| eq_global_eroded_pillars | `pillarMask` | OPEN_SIMPLEX_2 (freq 0.015) | No |
| eq_global_eroded_pillars | `pillarVariation` | OPEN_SIMPLEX_2 (freq 0.01, salt 1) | No |
| eq_stratified_land | `heightmap` | DOMAIN_WARP of elevation | No |
| eq_spikes | `spikeDirection` | OPEN_SIMPLEX_2 (freq 0.0005) | **Yes** — identical in eq_submerged_spikes |
| eq_spikes | `spikes` (nested EXPRESSION with mask/spike/platform sub-samplers) | EXPRESSION wrapping CELLULAR + PROBABILITY | **Yes** — identical in eq_submerged_spikes |
| eq_ocean_trench | `openings` | EXPRESSION_NORMALIZER of OPEN_SIMPLEX_2 | No |
| eq_ocean_trench | `ceilingSpikes` | CUBIC_SPLINE of CELLULAR | No |

### Already Pack-Level (No Change Needed)

- `elevation`, `elevationDetailedBlockHeight` — in `math/samplers/elevation.yml`, auto-wrapped in `LastValueSampler` ✅
- `biomeInfluence` — appears in multiple terrain expressions without a local samplers block, confirming it is already pack-level ✅
- `RiverOceanTransitioner`, `bedDepth`, `continentalRiverDist` — appear in ocean terrain without local definitions ✅

---

## Special Case: eq_spikes — Y-Warped Coordinates

The `spikes` call in eq_spikes uses y-warped coordinates:

```yaml
spikes(
  x + y*spikeSlantStrength*spikeDirection(x, z),
  z + y*spikeSlantStrength*spikeDirection(x, z+1000)
)
```

`spikes` itself is a pure 2D sampler (takes only x,z arguments). However, it receives DIFFERENT (x,z) arguments at each y-level due to the warp. This means:

- `spikes` **can** be extracted to pack level (it is a valid standalone 2D sampler)
- `spikeDirection` **can** be extracted to pack level (pure 2D, called at center coordinates)
- Within any single (x,z,y) blend loop pass, `spikeDirection(x,z)` is evaluated at the same center coordinates for all 49 blend positions → `LastValueSampler` on the pack-level `spikeDirection` gives 48 hits out of 49 calls
- The warped coordinates passed to `spikes` are constant within a single (x,z,y) pass (since both x,z and y are fixed per blend loop) → `LastValueSampler` on pack-level `spikes` similarly gives 48 hits per pass
- Cross-y hits: warped coordinates change with y → cache miss on each new y-level (correct behavior, no degradation)

---

## New File: `math/samplers/terrain_2d.yml`

Create this file in ORIGEN2. All 2D entries are automatically wrapped in `LastValueSampler` by the engine at pack load time.

```yaml
# Pack-level 2D terrain sub-samplers.
# All 2D entries here are automatically wrapped in LastValueSampler by the engine.
# Samplers accessed repeatedly in blend zones are additionally wrapped in type: CACHE
# for a larger hit window beyond the single LastValueSampler slot.

# ─── EQ_ROCKY ───────────────────────────────────────────────────────────────

terrain_rocky_cellBase: &cellBase           # shared anchor, not a sampler itself
  dimensions: 2
  type: CELLULAR
  frequency: 0.012

terrain_rocky_cellMask:
  dimensions: 2
  type: PROBABILITY
  sampler:
    type: OPEN_SIMPLEX_2
    frequency: 0.005

terrain_rocky_cellDistance:
  dimensions: 2
  type: FBM
  octaves: 4
  lacunarity: 2.4
  gain: 0.4
  sampler:
    <<: *cellBase
    return: Distance2Div

terrain_rocky_cellLookup:
  dimensions: 2
  <<: *cellBase
  return: NoiseLookup
  salt-lookup: false
  lookup:
    type: EXPRESSION
    expression: "elevation(x, z)"

terrain_rocky_elevationWarped:
  dimensions: 2
  type: DOMAIN_WARP
  amplitude: 5
  warp:
    type: FBM
    sampler:
      type: OPEN_SIMPLEX_2
      salt: 62098
  sampler:
    type: EXPRESSION
    expression: "elevation(x, z)"

# ─── EQ_GLOBAL_ERODED_PILLARS ────────────────────────────────────────────────

terrain_pillar_mask:
  dimensions: 2
  type: OPEN_SIMPLEX_2
  frequency: 0.015

terrain_pillar_variation:
  dimensions: 2
  type: OPEN_SIMPLEX_2
  frequency: 0.01
  salt: 1

# ─── EQ_STRATIFIED_LAND ──────────────────────────────────────────────────────

terrain_stratified_heightmap:
  dimensions: 2
  type: DOMAIN_WARP
  amplitude: 10
  warp:
    type: OPEN_SIMPLEX_2
    frequency: 0.03
    salt: 4912
  sampler:
    type: EXPRESSION
    expression: "elevation(x, z)"

# ─── EQ_SPIKES / EQ_SUBMERGED_SPIKES (shared) ───────────────────────────────

terrain_spike_direction:
  dimensions: 2
  type: OPEN_SIMPLEX_2
  frequency: 0.0005

terrain_spikes:
  dimensions: 2
  type: EXPRESSION
  expression: |
    maskSmooth(bigSpikeHeight, bigSpikeMaskNone, bigSpikeMaskFull, mask(x,z))
    + maskSmooth(littleSpikeHeight, littleSpikeMaskNone, littleSpikeMaskFull, mask(x,z))
    + maskSmooth(platformHeight, platformMaskNone, platformMaskFull, platform(x,z))
    # ... [full expression transplanted from eq_spikes.yml:spikes — verify exact form]
  samplers:
    mask:       # PROBABILITY of FBM(OPEN_SIMPLEX_2, octaves 2, freq 0.006)
    spike:      # LINEAR(-1, 0.2) of CELLULAR(freq 0.03, salt 1)
    platform:   # LINEAR(-1, 0.2) of CELLULAR(CellValue, freq 0.035, salt 2)
  variables:
    "<<": [ $customization.yml ]   # spikeCap, bigSpikeHeight, bigSpikeMaskNone, etc.

# ─── AQUATIC (sampler-2d path — ElevationInterpolator) ───────────────────────

terrain_ocean_trench_openings:
  dimensions: 2
  type: EXPRESSION_NORMALIZER
  expression: "|in|"
  sampler:
    type: OPEN_SIMPLEX_2
    frequency: 0.001
    salt: 9

terrain_ocean_trench_ceiling_spikes:
  dimensions: 2
  type: CUBIC_SPLINE
  # ... [transplanted from eq_ocean_trench.yml inline definition — verify exact form]
  sampler:
    type: CELLULAR
    distance: Euclidean
    frequency: 0.06
```

---

## Updates to Abstract Terrain Files

### eq_rocky.yml

Remove the full `samplers:` block. Update the expression to use pack-level names:

```yaml
terrain:
  sampler:
    dimensions: 3
    type: EXPRESSION
    expression: |
      -y + base + elevationDetailedBlockHeight(x,z)
      + scale * herp(terrain_rocky_cellDistance(x, z) + lerp(terrain_rocky_cellMask(x, z), 0.7, 0, 1, 1),
          -0.2, terrain_rocky_elevationWarped(x, z),
          -0.75, terrain_rocky_cellLookup(x, z)+0.02
        ) * biomeInfluence(x,z)
    # samplers: block removed entirely
```

### eq_global_eroded_pillars.yml

Remove `pillarMask` and `pillarVariation` from the `samplers:` block. Keep `pillarErosion` and `pillarWarp` (both are 3D — unchanged). Update expression:

```yaml
# pillarMask(x, z)      → terrain_pillar_mask(x, z)
# pillarVariation(x, z) → terrain_pillar_variation(x, z)
# pillarErosion(x,y,z) and pillarWarp(x,y,z) remain inline (3D — no change)
```

### eq_stratified_land.yml

Remove `heightmap` from the `samplers:` block. Update expression:

```yaml
# heightmap(x, z) → terrain_stratified_heightmap(x, z)
```

### eq_spikes.yml and eq_submerged_spikes.yml

Remove `spikeDirection` and `spikes` (and all of spikes' nested samplers) from both files. Replace with shared pack-level references. The variable block (spikeSlantStrength, spikeCap, etc.) remains in each terrain file — `terrain_spikes` must accept those via a `variables:` reference or inline override.

```yaml
# spikeDirection(x, z)      → terrain_spike_direction(x, z)
# spikeDirection(x, z+1000) → terrain_spike_direction(x, z+1000)
# spikes(warped_x, warped_z) → terrain_spikes(warped_x, warped_z)
#
# Variables (spikeSlantStrength, spikeCap, bigSpikeHeight, etc.) remain
# declared in the individual terrain files and flow into terrain_spikes
# via the variables resolution mechanism.
```

### eq_ocean_trench.yml

Remove `openings` and `ceilingSpikes` from the `samplers:` block. Update the 3D terrain expression:

```yaml
# openings(x, z)      → terrain_ocean_trench_openings(x, z)
# ceilingSpikes(x, z) → terrain_ocean_trench_ceiling_spikes(x, z)
```

---

## Which Samplers Need Explicit `type: CACHE` vs. `LastValueSampler` Only

`LastValueSampler` (single slot, auto-applied to all pack-level 2D samplers) is sufficient when:
- All blend loop positions use the same center (absoluteX, absoluteZ) → same key → hits for positions 2–49
- Between any two calls to the same pack-level sampler, no call with a different (x,z) intervenes — this holds because the ChunkInterpolator x→z→y loop completes all y-levels at one center before moving to the next

`LastValueSampler` is sufficient for all samplers in this plan under the current ChunkInterpolator access pattern.

**Explicit `type: CACHE` recommended for `terrain_spikes`:** Its nested CELLULAR and PROBABILITY sub-samplers are expensive. If `terrain_spikes` is later referenced from additional contexts (features, min-density, or post-processing), a multi-slot cache prevents LastValueSampler thrashing. Recommended setting: `exp: 8` (256 slots).

---

## Interaction with Plan 1 (Column Cache)

| Optimization | What it eliminates | Where it acts |
|---|---|---|
| Plan 1 (column cache) | Same-biome full sampler result recomputed at the same (x,z,y) across blend positions | Inside ChunkInterpolator blend loop: reduces 49×96 to ~5×96 evaluations per center |
| Plan 2 (pack-level) | 2D sub-sampler recomputed within each surviving per-biome evaluation at the same (x,z) | Inside each terrain expression: reduces 96 sub-sampler calls at the same (x,z) to ~1 via LastValueSampler |

Combined effect: the most expensive 2D sub-samplers (e.g., eq_rocky's `cellDistance` — FBM of CELLULAR) are evaluated at most **once per blend-zone biome type per center column** for the entire chunk construction, versus the current ~4,704 times at a full blend boundary.

---

## Implementation Order

1. Extract the full YAML for inline samplers from each terrain file (verify exact config matches the source — the above is reconstructed from exploration and should be cross-checked against the actual files before commit).
2. Create `math/samplers/terrain_2d.yml` with the pack-level definitions.
3. Update abstract terrain files one at a time, verifying with a test world after each change that terrain generation is visually identical.
4. The `terrain_spikes` / `terrain_spike_direction` extraction eliminates duplication between eq_spikes and eq_submerged_spikes — confirm both still produce correct terrain after the shared reference is in place.
5. Optionally add `type: CACHE, exp: 8` wrapper around `terrain_spikes` if profiling shows it is still a hotspot after the other changes.
