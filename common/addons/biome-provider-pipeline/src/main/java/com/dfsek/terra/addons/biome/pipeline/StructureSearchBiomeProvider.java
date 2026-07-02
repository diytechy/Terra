/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline;

import com.dfsek.seismic.type.sampler.Sampler;

import java.util.List;
import java.util.Optional;

import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


/**
 * Lightweight BiomeProvider for structure placement searches (e.g. stronghold ring
 * position computation). Evaluates a single classifier sampler and returns one of two
 * biomes based on a threshold, bypassing the full pipeline chunk cache.
 *
 * Intended for use only via getStructurePlacementBiome() — not for terrain generation.
 */
public class StructureSearchBiomeProvider implements BiomeProvider {

    private final Sampler classifier;
    private final double threshold;
    private final Biome eligibleBiome;
    private final Biome ineligibleBiome;

    public StructureSearchBiomeProvider(Sampler classifier, double threshold,
                                        Biome eligibleBiome, Biome ineligibleBiome) {
        this.classifier = classifier;
        this.threshold = threshold;
        this.eligibleBiome = eligibleBiome;
        this.ineligibleBiome = ineligibleBiome;
    }

    @Override
    public Biome getBiome(int x, int y, int z, long seed) {
        return classifier.getSample(seed, x, z) >= threshold ? eligibleBiome : ineligibleBiome;
    }

    @Override
    public Optional<Biome> getStructurePlacementBiome(int x, int z, long seed) {
        return Optional.of(getBiome(x, 0, z, seed));
    }

    @Override
    public Iterable<Biome> getBiomes() {
        return List.of(eligibleBiome, ineligibleBiome);
    }
}
