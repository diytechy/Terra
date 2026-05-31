/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline;

import com.dfsek.seismic.type.sampler.Sampler;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.jspecify.annotations.Nullable;

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

    private final LoadingCache<SeededVector2Key, BiomeChunk> biomeChunkCache;
    private final int chunkSize;
    private final int resolution;
    private final Sampler mutator;
    private final double noiseAmp;
    private final Set<Biome> biomes;
    private final Profiler profiler;
    private final @Nullable StructureSearchBiomeProvider structureFastPath;
    private final boolean interpolate;
    private final @Nullable Sampler interpolationSampler;

    public PipelineBiomeProvider(Pipeline pipeline, int resolution, Sampler mutator, double noiseAmp, Profiler profiler) {
        this(pipeline, resolution, mutator, noiseAmp, profiler, null, false, null);
    }

    public PipelineBiomeProvider(Pipeline pipeline, int resolution, Sampler mutator, double noiseAmp, Profiler profiler,
                                 @Nullable StructureSearchBiomeProvider structureFastPath) {
        this(pipeline, resolution, mutator, noiseAmp, profiler, structureFastPath, false, null);
    }

    public PipelineBiomeProvider(Pipeline pipeline, int resolution, Sampler mutator, double noiseAmp, Profiler profiler,
                                 @Nullable StructureSearchBiomeProvider structureFastPath,
                                 boolean interpolate, @Nullable Sampler interpolationSampler) {
        this.profiler = profiler;
        this.resolution = resolution;
        this.mutator = mutator;
        this.noiseAmp = noiseAmp;
        // Interpolation only has meaning when more than one block maps to a cell.
        this.interpolate = interpolate && resolution > 1;
        this.interpolationSampler = interpolationSampler;
        this.chunkSize = pipeline.getChunkSize();
        this.biomeChunkCache = Caffeine.newBuilder()
            .maximumSize(256)
            .build(pipeline::generateChunk);

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
        this.structureFastPath = structureFastPath;
    }

    @Override
    public Optional<Biome> getStructurePlacementBiome(int x, int z, long seed) {
        if(structureFastPath == null) return Optional.empty();
        return structureFastPath.getStructurePlacementBiome(x, z, seed);
    }

    @Override
    public Biome getBiome(int x, int y, int z, long seed) {
        return getBiome(x, z, seed);
    }

    public Biome getBiome(int x, int z, long seed) {

        x += (int) (mutator.getSample(seed + 1, x, z) * noiseAmp);
        z += (int) (mutator.getSample(seed + 2, x, z) * noiseAmp);

        if(!interpolate) {
            // Legacy path: snap to a single resolution cell. Truncating division is
            // retained here so existing worlds generate identically.
            return cellBiome(x / resolution, z / resolution, seed);
        }

        // Sub-resolution interpolation. Cell samples sit at the cell's lower-left
        // corner (world coordinate = gridIndex * resolution), so the block's
        // fractional offset within the cell gives the bilinear weights toward the
        // adjacent cells. The per-block roll then selects one of the four cells with
        // probability equal to its weight. Biomes are categorical, so this dithers
        // between samples rather than averaging them, breaking the axis-aligned
        // border steps into a noise-feathered gradient. Each call still performs
        // exactly one chunk-cache lookup.
        int gx = Math.floorDiv(x, resolution);
        int gz = Math.floorDiv(z, resolution);
        double tx = (double) Math.floorMod(x, resolution) / resolution;
        double tz = (double) Math.floorMod(z, resolution) / resolution;

        double roll = interpolationSampler != null
            ? clamp01((interpolationSampler.getSample(seed + 3, x, z) + 1.0) * 0.5)
            : hashRoll(seed, x, z);

        double w00 = (1.0 - tx) * (1.0 - tz);
        double w10 = tx * (1.0 - tz);
        double w01 = (1.0 - tx) * tz;

        if(roll < w00) return cellBiome(gx, gz, seed);
        if(roll < w00 + w10) return cellBiome(gx + 1, gz, seed);
        if(roll < w00 + w10 + w01) return cellBiome(gx, gz + 1, seed);
        return cellBiome(gx + 1, gz + 1, seed);
    }

    /**
     * Resolves the biome for a single resolution-grid cell, identified by its grid
     * coordinates (world coordinate / resolution).
     */
    private Biome cellBiome(int gridX, int gridZ, long seed) {
        int chunkX = Math.floorDiv(gridX, chunkSize);
        int chunkZ = Math.floorDiv(gridZ, chunkSize);

        int chunkWorldX = chunkX * chunkSize;
        int chunkWorldZ = chunkZ * chunkSize;

        int xInChunk = gridX - chunkWorldX;
        int zInChunk = gridZ - chunkWorldZ;

        return biomeChunkCache.get(new SeededVector2Key(chunkWorldX, chunkWorldZ, seed)).get(xInChunk, zInChunk).getBiome();
    }

    private static double clamp01(double v) {
        if(v <= 0.0) return 0.0;
        return v >= 1.0 ? Math.nextDown(1.0) : v;
    }

    /**
     * Deterministic per-block white-noise hash in {@code [0, 1)} used as the default
     * interpolation roll when no sampler is configured (splitmix64 finalizer).
     */
    private static double hashRoll(long seed, int x, int z) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= (x & 0xFFFFFFFFL) * 0xC2B2AE3D27D4EB4FL;
        h ^= (z & 0xFFFFFFFFL) * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
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
}
