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
import java.util.concurrent.locks.ReentrantLock;
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

    private static final int ENTRIES_PER_THREAD = 4;

    private final Pipeline pipeline;
    private final BiomeChunkCache chunkCache;
    private final int chunkSize;
    private final int resolution;
    private final Sampler mutator;
    private final double noiseAmp;
    private final Set<Biome> biomes;
    private final Profiler profiler;

    public PipelineBiomeProvider(Pipeline pipeline, int resolution, Sampler mutator, double noiseAmp, Profiler profiler,
                                 int generationThreads) {
        this.profiler = profiler;
        this.pipeline = pipeline;
        this.resolution = resolution;
        this.mutator = mutator;
        this.noiseAmp = noiseAmp;
        this.chunkSize = pipeline.getChunkSize();
        int stripeCount = Math.max(1, generationThreads);
        this.chunkCache = new BiomeChunkCache(ENTRIES_PER_THREAD * stripeCount, stripeCount);

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

        BiomeChunk chunk = chunkCache.get(chunkWorldX, chunkWorldZ, seed, pipeline);
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
        private final int stripeCount;
        private final int entriesPerStripe;
        private final int[] keyX;
        private final int[] keyZ;
        private final long[] keySeed;
        private final BiomeChunk[] chunks;
        private final int[] age;
        private final int[] tick;
        private final ReentrantLock[] locks;

        BiomeChunkCache(int totalSize, int stripeCount) {
            this.stripeCount = stripeCount;
            this.entriesPerStripe = totalSize / stripeCount;
            this.keyX = new int[totalSize];
            this.keyZ = new int[totalSize];
            this.keySeed = new long[totalSize];
            this.chunks = new BiomeChunk[totalSize];
            this.age = new int[totalSize];
            this.tick = new int[stripeCount];
            this.locks = new ReentrantLock[stripeCount];
            for(int i = 0; i < stripeCount; i++) {
                locks[i] = new ReentrantLock();
            }
        }

        BiomeChunk get(int chunkWorldX, int chunkWorldZ, long seed, Pipeline pipeline) {
            int stripe = Math.floorMod(chunkWorldX * 31 + chunkWorldZ, stripeCount);
            int start = stripe * entriesPerStripe;
            int end = start + entriesPerStripe;

            locks[stripe].lock();
            try {
                // Search for existing entry in this stripe
                for(int i = start; i < end; i++) {
                    if(chunks[i] != null && keyX[i] == chunkWorldX && keyZ[i] == chunkWorldZ && keySeed[i] == seed) {
                        age[i] = ++tick[stripe];
                        return chunks[i];
                    }
                }

                // Miss — evict the oldest entry in this stripe
                int oldest = start;
                for(int i = start + 1; i < end; i++) {
                    if(age[i] < age[oldest]) oldest = i;
                }

                BiomeChunk chunk = pipeline.generateChunk(new SeededVector2Key(chunkWorldX, chunkWorldZ, seed));
                keyX[oldest] = chunkWorldX;
                keyZ[oldest] = chunkWorldZ;
                keySeed[oldest] = seed;
                chunks[oldest] = chunk;
                age[oldest] = ++tick[stripe];
                return chunk;
            } finally {
                locks[stripe].unlock();
            }
        }
    }
}
