package com.dfsek.terra.addons.chunkgenerator.config.noise;

import com.dfsek.seismic.type.sampler.Sampler;

import org.jetbrains.annotations.Nullable;


/**
 * Immutable, cacheable record holding the shareable sampler configuration for a biome.
 * Separated from {@link BiomeNoiseProperties} so that Tectonic's session-scoped type-load cache
 * can deduplicate instances across biomes that inherit the same sampler config from a shared parent.
 */
public record BiomeNoiseSamplers(
    Sampler base,
    Sampler elevation,
    Sampler carving,
    int blendDistance,
    int blendStep,
    double blendWeight,
    double elevationWeight,
    @Nullable Sampler minDensity,
    boolean minDensitySmooth,
    double minDensitySmoothK,
    @Nullable Sampler densityFloor
) {}
