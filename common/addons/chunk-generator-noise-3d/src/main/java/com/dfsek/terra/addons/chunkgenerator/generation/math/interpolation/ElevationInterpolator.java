/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation;

import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class ElevationInterpolator {
    private static final ThreadLocal<BiomeNoiseProperties[][]> GENS_POOL = new ThreadLocal<>();

    private static BiomeNoiseProperties[][] acquireGens(int size) {
        BiomeNoiseProperties[][] arr = GENS_POOL.get();
        if(arr == null || arr.length < size) {
            arr = new BiomeNoiseProperties[size][size];
            GENS_POOL.set(arr);
        }
        return arr;
    }

    private final double[][] values = new double[18][18];

    public ElevationInterpolator(long seed, int chunkX, int chunkZ, BiomeProvider provider, int smooth,
                                 PropertyKey<BiomeNoiseProperties> noisePropertiesKey) {
        int xOrigin = chunkX << 4;
        int zOrigin = chunkZ << 4;

        BiomeNoiseProperties[][] gens = acquireGens(18 + 2 * smooth);

        // Precompute generators.
        for(int x = -1 - smooth; x <= 16 + smooth; x++) {
            for(int z = -1 - smooth; z <= 16 + smooth; z++) {
                int bx = xOrigin + x;
                int bz = zOrigin + z;
                gens[x + 1 + smooth][z + 1 + smooth] =
                    provider
                        .getBaseBiome(bx, bz, seed)
                        .orElseGet(() -> provider.getBiome(bx, 0, bz, seed)) // kind of a hack
                        .getContext()
                        .get(noisePropertiesKey);
            }
        }

        for(int x = -1; x <= 16; x++) {
            for(int z = -1; z <= 16; z++) {
                double noise = 0;
                double div = 0;

                for(int xi = -smooth; xi <= smooth; xi++) {
                    for(int zi = -smooth; zi <= smooth; zi++) {
                        BiomeNoiseProperties gen = gens[x + 1 + smooth + xi][z + 1 + smooth + zi];
                        double w = gen.samplers().elevationWeight();
                        if(w > 0) {
                            noise += gen.samplers().elevation().getSample(seed, xOrigin + x, zOrigin + z) * w;
                            div += w;
                        }
                    }
                }
                values[x + 1][z + 1] = noise / div;
            }
        }
    }

    public double getElevation(int x, int z) {
        return values[x + 1][z + 1];
    }
}
