package com.dfsek.terra.addons.chunkgenerator.config.noise;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * Immutable, cacheable record holding the shareable sampler configuration for a biome.
 * Separated from {@link BiomeNoiseProperties} so that Tectonic's session-scoped type-load cache
 * can deduplicate instances across biomes that inherit the same sampler config from a shared parent,
 * while keeping {@link ThreadLocalNoiseHolder} (which must remain unique per biome) outside the
 * cacheable record.
 */
public record BiomeNoiseSamplers(
    Sampler base,
    Sampler elevation,
    Sampler carving,
    int blendDistance,
    int blendStep,
    double blendWeight,
    double elevationWeight
) {}
