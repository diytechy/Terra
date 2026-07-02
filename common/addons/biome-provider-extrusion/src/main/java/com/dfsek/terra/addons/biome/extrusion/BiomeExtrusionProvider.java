package com.dfsek.terra.addons.biome.extrusion;

import com.dfsek.seismic.type.sampler.Sampler;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.dfsek.terra.addons.biome.extrusion.api.Extrusion;
import com.dfsek.terra.addons.biome.extrusion.utils.ExtrusionPipeline;
import com.dfsek.terra.addons.biome.extrusion.utils.ExtrusionPipelineFactory;
import com.dfsek.terra.api.util.Column;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class BiomeExtrusionProvider implements BiomeProvider {
    public final ExtrusionPipeline pipeline;
    private final BiomeProvider delegate;
    private final Set<Biome> biomes;
    private final int resolution;
    private final Sampler xzBlendSampler;
    private final double xzBlendAmplitude;
    private final boolean terrainWarpXZ;
    private final Sampler yBlendSampler;
    private final double yBlendAmplitude;
    private final boolean terrainWarpY;

    public BiomeExtrusionProvider(BiomeProvider delegate, List<Extrusion> extrusions, int resolution) {
        this(delegate, extrusions, resolution, Sampler.zero(), 0d, true, Sampler.zero(), 0d, true);
    }

    public BiomeExtrusionProvider(BiomeProvider delegate, List<Extrusion> extrusions, int resolution,
                                  Sampler xzBlendSampler, double xzBlendAmplitude, boolean terrainWarpXZ,
                                  Sampler yBlendSampler, double yBlendAmplitude, boolean terrainWarpY) {
        this.delegate = delegate;
        this.xzBlendSampler = xzBlendSampler;
        this.xzBlendAmplitude = xzBlendAmplitude;
        this.terrainWarpXZ = terrainWarpXZ;
        this.yBlendSampler = yBlendSampler;
        this.yBlendAmplitude = yBlendAmplitude;
        this.terrainWarpY = terrainWarpY;
        this.biomes = delegate.stream().collect(Collectors.toSet());
        extrusions.forEach(e -> biomes.addAll(e.getBiomes()));

        this.pipeline = ExtrusionPipelineFactory.create(extrusions);

        this.resolution = resolution;
    }

    int blendX(int x, int z, long seed) {
        return x + (int) (xzBlendSampler.getSample(seed + 1, x, z) * xzBlendAmplitude);
    }

    int blendZ(int x, int z, long seed) {
        return z + (int) (xzBlendSampler.getSample(seed + 2, x, z) * xzBlendAmplitude);
    }

    int blendY(int x, int y, int z, long seed) {
        return y + (int) (yBlendSampler.getSample(seed, x, z) * yBlendAmplitude);
    }

    @Override
    public Biome getBiome(int x, int y, int z, long seed) {
        Biome delegated = delegate.getBiome(x, y, z, seed);
        return pipeline.extrude(delegated, blendX(x, z, seed), blendY(x, y, z, seed), blendZ(x, z, seed), seed);
    }

    @Override
    public Column<Biome> getColumn(int x, int z, long seed, int min, int max) {
        // Feature/palette queries always receive the coordinate warp.
        return delegate.getBaseBiome(x, z, seed)
            .map(base -> (Column<Biome>) new BaseBiomeColumn(this, base, min, max, x, z, seed, true, true))
            .orElseGet(() -> BiomeProvider.super.getColumn(x, z, seed, min, max));
    }

    @Override
    public Column<Biome> getColumnForTerrain(int x, int z, long seed, int min, int max) {
        // Terrain noise queries honour the terrain-coordinate-noise-warp toggles so a pack
        // can warp palette/feature placement while keeping terrain noise on raw coordinates.
        return delegate.getBaseBiome(x, z, seed)
            .map(base -> (Column<Biome>) new BaseBiomeColumn(this, base, min, max, x, z, seed, terrainWarpXZ, terrainWarpY))
            .orElseGet(() -> BiomeProvider.super.getColumnForTerrain(x, z, seed, min, max));
    }

    @Override
    public Optional<Biome> getBaseBiome(int x, int z, long seed) {
        return delegate.getBaseBiome(x, z, seed);
    }

    @Override
    public Optional<Biome> getStructurePlacementBiome(int x, int z, long seed) {
        // Structure searches only need land/ocean eligibility, which is a horizontal
        // (delegate) property; extrusion only rewrites biomes by Y. Forward to the
        // delegate so its structure-search fast path (if any) engages.
        return delegate.getStructurePlacementBiome(x, z, seed);
    }

    @Override
    public Iterable<Biome> getBiomes() {
        return biomes;
    }

    @Override
    public int resolution() {
        return resolution;
    }

    public BiomeProvider getDelegate() {
        return delegate;
    }
}
