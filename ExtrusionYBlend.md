# Plan: Extrusion Y-Blend

## Context

The pipeline `blend` system warps X/Z coordinates before the biome grid is consulted, producing organic wavy biome borders in the horizontal plane. The extrusion system (which controls vertical biome distribution — cave biomes, deep dark, etc.) has no equivalent mechanism: its Y range boundaries are perfectly flat horizontal planes. This adds an analogous `blend` block to the extrusion provider that offsets the Y coordinate before the extrusion pipeline runs, making vertical biome boundaries vary naturally by horizontal position.

## Design Decisions

**Y-only warp** — X/Z warping is already served by the pipeline's `blend`; this targets the orthogonal axis.

**2D noise (x, z) → Y offset** — Sampler is evaluated in the XZ plane, producing a consistent Y shift for an entire column. This is the same convention as the pipeline blend (which also uses 2D sampling). A 3D sampler would make the warp value change as Y changes, which can cause the effective Y to retreat as actual Y rises — a pathological artifact that 2D avoids entirely.

**Block-space amplitude** — The warp is applied to block-coordinate Y directly, before any resolution concerns. An `amplitude: 16` means ±16 blocks of boundary warp. Consistent with the pipeline blend, which also operates in block space before resolution division.

**No-op default** — `blendSampler = Sampler.zero()`, `blendAmplitude = 0.0` so all existing configs are unaffected.

---

## Files to Change (3 files)

### 1. `common/addons/biome-provider-extrusion/src/main/java/com/dfsek/terra/addons/biome/extrusion/config/BiomeExtrusionTemplate.java`

Add two `@Value` fields mirroring the pipeline template pattern:

```java
import com.dfsek.seismic.type.sampler.Sampler;

@Value("blend.sampler")
@Default
@Description("A sampler to use for blending extrusion boundaries via Y-axis domain warping.")
private @Meta Sampler blendSampler = Sampler.zero();

@Value("blend.amplitude")
@Default
@Description("The amplitude (in blocks) of the Y-coordinate warp applied before extrusion evaluation.")
private @Meta double blendAmplitude = 0d;
```

Update `get()` to pass these to the provider:
```java
return new BiomeExtrusionProvider(provider, extrusions, resolution, effectiveYResolution,
                                   blendSampler, blendAmplitude, profiler);
```

### 2. `common/addons/biome-provider-extrusion/src/main/java/com/dfsek/terra/addons/biome/extrusion/BiomeExtrusionProvider.java`

Add two fields:
```java
private final Sampler blendSampler;
private final double blendAmplitude;
```

Extend the constructor signature to accept them and assign.

Add a package-private helper (called from both `getBiome` and `BaseBiomeColumn`):
```java
int blendY(int x, int y, int z, long seed) {
    return y + (int) (blendSampler.getSample(seed, x, z) * blendAmplitude);
}
```

Update `getBiome()`:
```java
@Override
public Biome getBiome(int x, int y, int z, long seed) {
    Biome delegated = delegate.getBiome(x, y, z, seed);
    return pipeline.extrude(delegated, x, blendY(x, y, z, seed), z, seed);
}
```

### 3. `common/addons/biome-provider-extrusion/src/main/java/com/dfsek/terra/addons/biome/extrusion/BaseBiomeColumn.java`

Update `get(int y)` to route through the provider's helper:
```java
@Override
public Biome get(int y) {
    return biomeProvider.pipeline.extrude(base, x, biomeProvider.blendY(x, y, z, seed), z, seed);
}
```

---

## YAML Usage (CHIMERA.yml example)

```yaml
biomes:
  type: EXTRUSION
  blend:
    sampler:
      type: OPEN_SIMPLEX_2
      frequency: 0.01
    amplitude: 16
  extrusions:
    ...
```

`amplitude` is in blocks. A value of 16 gives ±16 blocks of boundary variation. Lower frequency = larger-scale geographic variation in boundary elevation.

---

## Verification

1. Build the project and load the pack — no config errors on existing CHIMERA.yml (no blend block defined, defaults to zero amplitude).
2. Add a `blend` block to CHIMERA.yml with `amplitude: 20` and `OPEN_SIMPLEX_2` at `frequency: 0.01`.
3. Use BiomeTool or in-game to inspect the vertical biome column at varying X/Z positions — extrusion boundaries should visibly shift up/down by ±20 blocks depending on position rather than sitting at the same Y everywhere.
4. Confirm `getBiome()` and `getColumn()` paths both apply the warp (single-point queries and chunk generation should be consistent).
