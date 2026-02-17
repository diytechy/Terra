# Multi-Value Samplers in Terra

## Overview

The standard `Sampler` interface returns a single `double` from `getSample()`. However, there
are two patterns in the codebase where a sampler source provides more than one value:

1. **DerivativeSampler** - returns noise value + partial derivatives as a `double[]`
2. **ColorSampler** - returns a packed ARGB `int` encoding 4 channel values


## 1. DerivativeSampler (Noise + Derivatives)

### What it returns

`DerivativeSampler` extends `Sampler` and adds `getSampleDerivative()` methods:

- **2D**: `double[3]` = `[value, dX, dY]`
- **3D**: `double[4]` = `[value, dX, dY, dZ]`

The regular `getSample()` method still works and returns only the noise value. The derivative
data is only computed when `getSampleDerivative()` is explicitly called.

### Which samplers support derivatives

All simplex-style noise samplers extend `DerivativeNoiseFunction`, which implements
`DerivativeSampler`:

- `OPEN_SIMPLEX_2`
- `OPEN_SIMPLEX_2S`
- `PERLIN`
- `SIMPLEX`

Fractal wrappers conditionally support derivatives if their input does:

- `FBM` (BrownianMotionSampler) - differentiable if input sampler is differentiable

`PSEUDOEROSION` consumes a `DerivativeSampler` as input to use the derivative for
erosion calculations.

### How to extract a single value

No special wrapper is needed. Since `DerivativeSampler` extends `Sampler`, calling
`getSample()` returns just the noise value. The derivatives are only computed on demand
via `getSampleDerivative()`.

In YAML configuration, derivative samplers are used like any other sampler. When a
config field requires a `DerivativeSampler` specifically (like PseudoErosion's `sampler`
field), the template validates that the provided sampler actually supports derivatives.

### YAML example - Using a derivative-capable sampler in PseudoErosion

```yaml
samplers:
  erosionNoise:
    dimensions: 2
    type: PSEUDOEROSION
    frequency: 1
    octaves: 4
    lacunarity: 2.0
    gain: 0.5
    slope-strength: 1.0
    branch-strength: 1.0
    strength: 0.04
    erosion-frequency: 0.02
    # This field requires a DerivativeSampler - simplex types qualify
    sampler:
      type: OPEN_SIMPLEX_2
      frequency: 0.02
```

### YAML example - Using a derivative sampler as a plain noise source

Since DerivativeSampler extends Sampler, any derivative-capable sampler can be used
anywhere a regular sampler is expected. Only the single noise value is used, the
derivative is ignored:

```yaml
samplers:
  terrainBase:
    dimensions: 2
    type: FBM
    octaves: 4
    sampler:
      # OPEN_SIMPLEX_2 is a DerivativeSampler, but here it's used as a
      # plain noise source - only getSample() is called, derivatives ignored
      type: OPEN_SIMPLEX_2
      frequency: 0.01
```


## 2. ColorSampler -> ChannelSampler (Extracting from packed ARGB)

### What it returns

`ColorSampler` is a functional interface that returns an `int` representing a packed
ARGB color (4 values encoded in a single integer):

- Alpha (bits 24-31)
- Red (bits 16-23)
- Green (bits 8-15)
- Blue (bits 0-7)

### How to extract a single value

The `CHANNEL` sampler type (`ChannelSampler`) wraps a `ColorSampler` and extracts a
single channel as a `double`, converting the multi-value color into a standard `Sampler`.

Available channels: `RED`, `GREEN`, `BLUE`, `ALPHA`, `GRAYSCALE`

Options:
- `normalize` (default `true`) - maps the 0-255 channel range to -1 to 1
- `premultiply` (default `false`) - multiplies RGB channels by alpha before extraction

### YAML example - Extracting a channel from an image

```yaml
# Requires the library-image addon
samplers:
  heightFromRedChannel:
    dimensions: 2
    type: CHANNEL
    channel: RED
    normalize: true       # Map 0-255 to range [-1, 1]
    premultiply: false
    color-sampler:
      type: SINGLE_IMAGE  # or TILED_IMAGE, etc.
      image: heightmap.png

  moistureFromGreen:
    dimensions: 2
    type: CHANNEL
    channel: GREEN
    normalize: true
    color-sampler:
      type: SINGLE_IMAGE
      image: heightmap.png
```

This allows a single image to encode multiple data layers in its color channels,
with each `CHANNEL` sampler pulling out one layer as a standard noise value.


## 3. Using multi-value extractions in expressions

Once extracted to a single value, these samplers can be referenced in EXPRESSION
samplers like any other sampler:

```yaml
variables: &variables
  erosionScale: 0.02
  heightScale: 0.005

samplers:
  # A derivative-capable sampler used as plain noise input
  baseHeight:
    dimensions: 2
    type: FBM
    octaves: 4
    sampler:
      type: OPEN_SIMPLEX_2
      frequency: 0.005

  # Erosion that internally uses derivatives from its input sampler
  erosion:
    dimensions: 2
    type: PSEUDOEROSION
    frequency: 1
    octaves: 4
    strength: 0.04
    erosion-frequency: 0.02
    sampler:
      type: OPEN_SIMPLEX_2
      frequency: 0.02

  # Extract red channel from an image as a sampler
  imageHeight:
    dimensions: 2
    type: CHANNEL
    channel: RED
    normalize: true
    color-sampler:
      type: SINGLE_IMAGE
      image: terrain.png

  # Combine all of the above in an expression
  finalTerrain:
    dimensions: 2
    type: EXPRESSION
    expression: |
      baseHeight(x, z) + erosion(x, z) * 0.5 + imageHeight(x, z) * 0.3
    variables: *variables
    samplers:
      baseHeight:
        <<: *baseHeight  # Reference if defined with anchors, or inline
      erosion:
        <<: *erosion
      imageHeight:
        <<: *imageHeight
```


## Key source files

- `Sampler.java` (Seismic) - Base interface, single `double` return via `getSample()`
- `DerivativeSampler.java` (Seismic) - Extends Sampler, adds `getSampleDerivative()` returning `double[]`
- `DerivativeNoiseFunction.java` (Seismic) - Abstract base for differentiable noise functions
- `DerivativeSamplerTemplate.java` (Terra) - Config template that validates differentiability
- `PseudoErosionTemplate.java` (Terra) - Consumes a DerivativeSampler for erosion
- `ColorSampler.java` (Terra) - Returns packed ARGB int
- `ChannelSampler.java` (Terra) - Extracts single channel from ColorSampler as a Sampler
- `ChannelSamplerTemplate.java` (Terra) - Config template for CHANNEL type
- `ImageLibraryAddon.java` (Terra) - Registers `CHANNEL` as a sampler type
