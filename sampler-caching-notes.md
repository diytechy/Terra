# Terra Sampler Caching Architecture

## Two Levels of Caching

There are two distinct caching mechanisms in Terra:

### 1. `CacheSampler` (explicit `CACHE` type) — the one with poor performance

This is the per-evaluation wrapper at `common/addons/config-noise-function/src/main/java/com/dfsek/terra/addons/noise/config/sampler/CacheSampler.java`. It's marked `@Experimental` and uses thread-local Caffeine caches with hard-coded sizes (256 for 2D, 981,504 for 3D). You must explicitly wrap a sampler with `type: CACHE` in config to use it.

### 2. `SamplerProvider` (chunk-level) — automatic caching during generation

This is the automatic cache in `common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/math/samplers/SamplerProvider.java`. It caches entire `Sampler3D` chunk objects (keyed by chunk x/z, seed, and height range), so when a chunk is revisited, the compiled noise grid doesn't need to be recomputed. This IS automatic — every call in `NoiseChunkGenerator3D.java:94` goes through it.

## Are pack-level samplers automatically cached?

**Not at the individual evaluation level.** When pack samplers (from the `samplers:` section of the pack YAML) are loaded in `NoiseAddon.java:164-166`, they're stored as-is in the `packSamplers` map. In `FunctionUtil.java:27-35`, they're converted directly to paralithic `NoiseFunction2`/`NoiseFunction3` wrappers with no caching layer applied.

**However**, the `SamplerProvider` does automatically cache at the chunk level. Since `Sampler3D` objects internally use the biome noise properties (which reference these pack samplers), the computed results are effectively cached per-chunk.

## Is it trivial to increase the cache size?

**Yes.** The `SamplerProvider` cache size is configured in `config.yml` at `PluginConfigImpl.java:68-70`:

```yaml
cache:
  sampler: 1024  # default value
```

Just change `1024` to a larger number. It directly controls the Caffeine `maximumSize` at `SamplerProvider.java:39`. Higher values = more memory but better generation performance when revisiting chunks.

### Other cache settings in config.yml

```yaml
cache:
  structure: 32    # default
  sampler: 1024    # default
  biome-provider: 32  # default
```

## Key source files

- `CacheSampler.java` — explicit per-evaluation cache wrapper (`@Experimental`, poor performance)
- `CacheSamplerTemplate.java` — config template for the CACHE sampler type
- `SamplerProvider.java` — automatic chunk-level Sampler3D cache
- `NoiseChunkGenerator3D.java` — chunk generator that uses SamplerProvider
- `PluginConfigImpl.java` — config.yml loading, defines `cache.sampler` default (1024)
- `NoiseAddon.java` — loads pack-level samplers (no automatic caching applied)
- `NoiseConfigPackTemplate.java` — defines the `samplers:` section of pack YAML
- `FunctionUtil.java` — converts pack samplers to paralithic functions (no caching layer)
