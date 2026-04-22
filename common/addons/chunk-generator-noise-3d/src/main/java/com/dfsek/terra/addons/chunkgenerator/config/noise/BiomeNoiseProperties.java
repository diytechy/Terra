package com.dfsek.terra.addons.chunkgenerator.config.noise;

import com.dfsek.terra.api.properties.Properties;


public record BiomeNoiseProperties(
    BiomeNoiseSamplers samplers
) implements Properties {}
