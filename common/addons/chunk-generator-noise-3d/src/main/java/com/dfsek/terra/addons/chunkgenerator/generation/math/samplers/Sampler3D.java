/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation.math.samplers;

import com.dfsek.seismic.math.floatingpoint.FloatingPointFunctions;

import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation.ChunkInterpolator;
import com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation.ElevationInterpolator;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class Sampler3D {
    private final ChunkInterpolator interpolator;
    private final ElevationInterpolator elevationInterpolator;

    public Sampler3D(int x, int z, long seed, int minHeight, int maxHeight, BiomeProvider provider, int elevationSmooth,
                     PropertyKey<BiomeNoiseProperties> noisePropertiesKey, int maxBlend, int blendMinY, int blendMaxY) {
        this.elevationInterpolator = new ElevationInterpolator(seed, x, z, provider, elevationSmooth, noisePropertiesKey);
        this.interpolator = new ChunkInterpolator(seed, x, z, provider,
            minHeight, maxHeight, noisePropertiesKey, maxBlend, blendMinY, blendMaxY);
    }

    public double sample(double x, double y, double z) {
        int rx = FloatingPointFunctions.round(x);
        int rz = FloatingPointFunctions.round(z);
        double density = interpolator.getNoise(x, y, z) + elevationInterpolator.getElevation(rx, rz);
        if(interpolator.hasFloor()) {
            density = Math.max(density, interpolator.getFloor(rx, FloatingPointFunctions.round(y), rz));
        }
        return density;
    }

    public double sample(int x, int y, int z) {
        double density = interpolator.getNoise(x, y, z) + elevationInterpolator.getElevation(x, z);
        if(interpolator.hasFloor()) {
            density = Math.max(density, interpolator.getFloor(x, y, z));
        }
        return density;
    }
}
