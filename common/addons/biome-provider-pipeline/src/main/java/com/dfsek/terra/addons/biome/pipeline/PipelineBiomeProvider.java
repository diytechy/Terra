/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline;

import com.dfsek.seismic.type.sampler.Sampler;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.dfsek.terra.addons.biome.pipeline.api.BiomeChunk;
import com.dfsek.terra.addons.biome.pipeline.api.Pipeline;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;
import com.dfsek.terra.addons.biome.pipeline.api.biome.PipelineBiome;
import com.dfsek.terra.api.registry.key.StringIdentifiable;
import com.dfsek.terra.api.util.Column;
import com.dfsek.terra.api.util.cache.SeededVector2Key;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.profiler.Profiler;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class PipelineBiomeProvider implements BiomeProvider {

    private static final int CACHE_SIZE = 4;

    private final Pipeline pipeline;
    private final ThreadLocal<BiomeChunkCache> chunkCache;
    private final int chunkSize;
    private final int resolution;
    private final Sampler mutator;
    private final double noiseAmp;
    private final Set<Biome> biomes;
    private final Profiler profiler;

    public PipelineBiomeProvider(Pipeline pipeline, int resolution, Sampler mutator, double noiseAmp, Profiler profiler) {
        this.profiler = profiler;
        this.pipeline = pipeline;
        this.resolution = resolution;
        this.mutator = mutator;
        this.noiseAmp = noiseAmp;
        this.chunkSize = pipeline.getChunkSize();
        this.chunkCache = ThreadLocal.withInitial(BiomeChunkCache::new);

        Set<PipelineBiome> biomeSet = new HashSet<>();
        pipeline.getSource().getBiomes().forEach(biomeSet::add);
        Iterable<PipelineBiome> result = biomeSet;
        for(Stage stage : pipeline.getStages()) {
            result = stage.getBiomes(result);
        }
        this.biomes = new HashSet<>();
        Iterable<PipelineBiome> finalResult = result;
        result.forEach(pipelineBiome -> {
            if(pipelineBiome.isPlaceholder()) {

                StringBuilder biomeList = new StringBuilder("\n");
                StreamSupport.stream(finalResult.spliterator(), false)
                    .sorted(Comparator.comparing(StringIdentifiable::getID))
                    .forEach(delegate -> biomeList
                        .append("    - ")
                        .append(delegate.getID())
                        .append(':')
                        .append(delegate.getClass().getCanonicalName())
                        .append('\n'));
                throw new IllegalArgumentException("Biome Pipeline leaks placeholder biome \"" + pipelineBiome.getID() +
                                                   "\". Ensure there is a stage to guarantee replacement of the placeholder biome. " +
                                                   "Biomes: " +
                                                   biomeList);
            }
            this.biomes.add(pipelineBiome.getBiome());
        });
    }

    @Override
    public Biome getBiome(int x, int y, int z, long seed) {
        return getBiome(x, z, seed);
    }

    public Biome getBiome(int x, int z, long seed) {

        x += (int) (mutator.getSample(seed + 1, x, z) * noiseAmp);
        z += (int) (mutator.getSample(seed + 2, x, z) * noiseAmp);

        x /= resolution;
        z /= resolution;

        int chunkX = Math.floorDiv(x, chunkSize);
        int chunkZ = Math.floorDiv(z, chunkSize);

        int chunkWorldX = chunkX * chunkSize;
        int chunkWorldZ = chunkZ * chunkSize;

        int xInChunk = x - chunkWorldX;
        int zInChunk = z - chunkWorldZ;

        BiomeChunk chunk = chunkCache.get().get(chunkWorldX, chunkWorldZ, seed, pipeline);
        return chunk.get(xInChunk, zInChunk).getBiome();
    }

    @Override
    public Iterable<Biome> getBiomes() {
        return biomes;
    }

    @Override
    public Optional<Biome> getBaseBiome(int x, int z, long seed) {
        return Optional.of(getBiome(x, z, seed));
    }

    @Override
    public Column<Biome> getColumn(int x, int z, long seed, int min, int max) {
        return new BiomePipelineColumn(this, min, max, x, z, seed);
    }

    @Override
    public int resolution() {
        return resolution;
    }

    private static final class BiomeChunkCache {
        private final int[] keyX = new int[CACHE_SIZE];
        private final int[] keyZ = new int[CACHE_SIZE];
        private final long[] keySeed = new long[CACHE_SIZE];
        private final BiomeChunk[] chunks = new BiomeChunk[CACHE_SIZE];
        private final int[] age = new int[CACHE_SIZE];
        private int tick;

        BiomeChunk get(int chunkWorldX, int chunkWorldZ, long seed, Pipeline pipeline) {
            // Search for existing entry
            for(int i = 0; i < CACHE_SIZE; i++) {
                if(chunks[i] != null && keyX[i] == chunkWorldX && keyZ[i] == chunkWorldZ && keySeed[i] == seed) {
                    age[i] = ++tick;
                    return chunks[i];
                }
            }

            // Miss — evict the oldest entry
            int oldest = 0;
            for(int i = 1; i < CACHE_SIZE; i++) {
                if(age[i] < age[oldest]) oldest = i;
            }

            BiomeChunk chunk = pipeline.generateChunk(new SeededVector2Key(chunkWorldX, chunkWorldZ, seed));
            keyX[oldest] = chunkWorldX;
            keyZ[oldest] = chunkWorldZ;
            keySeed[oldest] = seed;
            chunks[oldest] = chunk;
            age[oldest] = ++tick;
            return chunk;
        }
    }
}
