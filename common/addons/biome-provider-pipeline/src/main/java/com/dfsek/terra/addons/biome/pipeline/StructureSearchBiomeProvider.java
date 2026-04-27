/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline;

import com.dfsek.seismic.type.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(StructureSearchBiomeProvider.class);
    private static final AtomicBoolean LOGGED_FIRST_QUERY = new AtomicBoolean(false);

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
        LOGGER.info("[Terra] StructureSearchBiomeProvider active — eligible: {}, ineligible: {}, threshold: {}",
            eligibleBiome.getID(), ineligibleBiome.getID(), threshold);
    }

    @Override
    public Biome getBiome(int x, int y, int z, long seed) {
        return classifier.getSample(seed, x, z) >= threshold ? eligibleBiome : ineligibleBiome;
    }

    @Override
    public Optional<Biome> getStructurePlacementBiome(int x, int z, long seed) {
        if (LOGGED_FIRST_QUERY.compareAndSet(false, true)) {
            LOGGER.info("[Terra] StructureSearchBiomeProvider.getStructurePlacementBiome first query at ({}, {})", x, z);
        }
        return Optional.of(getBiome(x, 0, z, seed));
    }

    @Override
    public Iterable<Biome> getBiomes() {
        return List.of(eligibleBiome, ineligibleBiome);
    }
}
