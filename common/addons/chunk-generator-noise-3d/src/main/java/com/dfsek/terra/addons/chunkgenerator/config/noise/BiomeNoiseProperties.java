package com.dfsek.terra.addons.chunkgenerator.config.noise;

import com.dfsek.terra.api.properties.Properties;


/**
 * Per-biome noise configuration stored in the biome {@link com.dfsek.terra.api.properties.Context}.
 * <p>
 * {@link BiomeNoiseSamplers} holds the shareable, immutable sampler configuration and is safe to
 * cache and deduplicate across biomes that inherit the same config from a common parent.
 * {@link ThreadLocalNoiseHolder} is intentionally kept here (not inside {@code BiomeNoiseSamplers})
 * because it caches per-thread noise results and must remain unique per biome.
 */
public record BiomeNoiseProperties(
    BiomeNoiseSamplers samplers,
    ThreadLocalNoiseHolder noiseHolder
) implements Properties {}
