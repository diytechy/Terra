/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation;

import com.dfsek.seismic.type.sampler.Sampler;

import com.dfsek.terra.addons.chunkgenerator.SamplerFloorFeature;
import com.dfsek.terra.addons.chunkgenerator.TerrainDebug;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseSamplers;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.util.Column;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


/**
 * Class to abstract away the Interpolators needed to generate a chunk.<br>
 * Contains method to get interpolated noise at a coordinate within the chunk.
 */
public class ChunkInterpolator {
    private final Interpolator3[][][] interpGrid;
    // Null when the sampler-floor feature is compile-time disabled, OR when it is enabled but
    // no sparse point in this chunk carries a real floor value. See {@link SamplerFloorFeature}.
    private final Interpolator3[][][] floorGrid;

    private final int min;
    private final int max;

    // Thread-local biome weight map reused across all (x,z) centers within a construction.
    // Avoids per-center allocation; safe because construction is single-threaded per chunk.
    private static final ThreadLocal<BiomeWeightMap> BLEND_MAP =
        ThreadLocal.withInitial(BiomeWeightMap::new);

    /**
     * Instantiates a 3D ChunkInterpolator3D at a pair of chunk coordinates.
     *
     * @param chunkX   X coordinate of the chunk.
     * @param chunkZ   Z coordinate of the chunk.
     * @param provider Biome Provider to use for biome fetching.
     * @param min
     * @param max
     */
    public ChunkInterpolator(long seed, int chunkX, int chunkZ, BiomeProvider provider, int min, int max,
                             PropertyKey<BiomeNoiseProperties> noisePropertiesKey, int maxBlend,
                             int blendMinY, int blendMaxY) {
        this.min = min;
        this.max = max;

        int xOrigin = chunkX << 4;
        int zOrigin = chunkZ << 4;

        final boolean debugChunk = TerrainDebug.ENABLED
            && TerrainDebug.isTargetChunk(chunkX, chunkZ)
            && TerrainDebug.isTargetSeed(seed);
        if(debugChunk) {
            TerrainDebug.LOG.info("[CI] Building ChunkInterpolator for chunk ({}, {})", chunkX, chunkZ);
        }

        int range = this.max - this.min + 1;

        int size = range >> 2;

        interpGrid = new Interpolator3[4][size][4];

        // Sparse storage uses float (32-bit) to halve memory vs double.
        // Interpolator3 accepts double; Java auto-widens float at the call site.
        float[][][] noiseStorage = new float[5][5][size + 1];
        // Float.NEGATIVE_INFINITY is the sentinel meaning "no floor defined at this sparse point".
        // Allocation is skipped entirely when the sampler-floor feature is compile-time disabled.
        float[][][] floorStorage = SamplerFloorFeature.ENABLED ? new float[5][5][size + 1] : null;

        // Option 5: Pre-scan the 5x5 center grid to compute the local max blend for this chunk.
        // This allows allocating a smaller columns array when high-blend outlier biomes are absent
        // from this chunk, avoiding the memory overhead of the global maximum.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Column<Biome>[] centerColumns = new Column[25];
        int localMaxBlend = 0;
        for(int x = 0; x < 5; x++) {
            int scaledX = x << 2;
            int absoluteX = xOrigin + scaledX;
            for(int z = 0; z < 5; z++) {
                int scaledZ = z << 2;
                int absoluteZ = zOrigin + scaledZ;
                Column<Biome> col = provider.getColumn(absoluteX, absoluteZ, seed, min, max);
                centerColumns[x * 5 + z] = col;
                for(int y = 0; y < size; y++) {
                    int scaledY = (y << 2) + min;
                    BiomeNoiseProperties props = col.get(scaledY).getContext().get(noisePropertiesKey);
                    int localBlend = props.samplers().blendDistance() * props.samplers().blendStep();
                    if(localBlend > localMaxBlend) localMaxBlend = localBlend;
                }
            }
        }

        int localMaxBlendAndChunk = 17 + 2 * localMaxBlend;

        @SuppressWarnings("unchecked")
        Column<Biome>[] columns = new Column[localMaxBlendAndChunk * localMaxBlendAndChunk];

        // Pre-populate center columns into the main columns array at their correct offsets.
        for(int x = 0; x < 5; x++) {
            int scaledX = x << 2;
            for(int z = 0; z < 5; z++) {
                int scaledZ = z << 2;
                int index = (scaledX + localMaxBlend) + localMaxBlendAndChunk * (scaledZ + localMaxBlend);
                columns[index] = centerColumns[x * 5 + z];
            }
        }

        BiomeWeightMap blendMap = BLEND_MAP.get();

        for(int x = 0; x < 5; x++) {
            int scaledX  = x << 2;
            int absoluteX = xOrigin + scaledX;
            for(int z = 0; z < 5; z++) {
                int scaledZ  = z << 2;
                int absoluteZ = zOrigin + scaledZ;

                Column<Biome> biomeColumn = centerColumns[x * 5 + z];

                // Read center-biome settings once per (x,z).
                // BiomePipelineColumn.get(y) returns the same Biome for every y,
                // so generationSettings, blend, and step are y-invariant.
                BiomeNoiseProperties generationSettings = biomeColumn.get(min)
                    .getContext()
                    .get(noisePropertiesKey);
                int step  = generationSettings.samplers().blendStep();
                int blend = generationSettings.samplers().blendDistance();

                // Build biome weight map once per (x,z):
                // fetch all blend columns into columns[], de-duplicate by biome identity,
                // and accumulate each unique biome's total weight.
                // Replaces the per-y column fetch and per-y 49-position blend loop.
                blendMap.reset();
                if(blend > 0) {
                    for(int xi = -blend; xi <= blend; xi++) {
                        for(int zi = -blend; zi <= blend; zi++) {
                            int blendX = xi * step;
                            int blendZ = zi * step;
                            int localIndex = (scaledX + localMaxBlend + blendX)
                                           + localMaxBlendAndChunk * (scaledZ + localMaxBlend + blendZ);
                            if(columns[localIndex] == null) {
                                columns[localIndex] = provider.getColumn(
                                    absoluteX + blendX, absoluteZ + blendZ, seed, min, max);
                            }
                            // .get(min) is a free field read on BiomePipelineColumn;
                            // result is identical for any y argument.
                            BiomeNoiseSamplers ns = columns[localIndex].get(min)
                                .getContext().get(noisePropertiesKey).samplers();
                            blendMap.accumulate(ns, ns.blendWeight());
                            if(SamplerFloorFeature.ENABLED && ns.densityFloor() == null) {
                                blendMap.allHaveFloor = false;
                            }
                        }
                    }
                }

                for(int y = 0; y < size; y++) {
                    int scaledY = (y << 2) + min;

                    double noise;
                    // floorValue is only meaningful when SamplerFloorFeature.ENABLED is true;
                    // when disabled, javac/JIT eliminates every read and write of it.
                    float floorValue = Float.NEGATIVE_INFINITY;

                    if(blend == 0 || scaledY < blendMinY || scaledY > blendMaxY) {
                        // Blend disabled: either the biome has blendDistance=0, or this Y level is
                        // outside the pack-configured blend range. Use center sample directly.
                        noise = generationSettings.samplers().base().getSample(seed, absoluteX, scaledY, absoluteZ);
                        if(SamplerFloorFeature.ENABLED) {
                            Sampler floor = generationSettings.samplers().densityFloor();
                            floorValue = (floor != null)
                                ? (float) floor.getSample(seed, absoluteX, scaledY, absoluteZ)
                                : Float.NEGATIVE_INFINITY;
                        }
                    } else if(blendMap.size == 1) {
                        // All neighbors share one biome: weighted average of identical values
                        // equals the center sample directly.
                        BiomeNoiseSamplers s = blendMap.samplers[0];
                        noise = s.base().getSample(seed, absoluteX, scaledY, absoluteZ);
                        if(SamplerFloorFeature.ENABLED) {
                            Sampler floor = s.densityFloor();
                            floorValue = (floor != null)
                                ? (float) floor.getSample(seed, absoluteX, scaledY, absoluteZ)
                                : Float.NEGATIVE_INFINITY;
                        }
                    } else {
                        // Heterogeneous blend zone: iterate unique biomes (typically 2-5)
                        // instead of all blend positions (up to 49).
                        // Mathematically equivalent: since all blend positions sharing a biome
                        // evaluate the sampler at identical center coordinates, their contributions
                        // collapse to sample(biome) * accumulatedWeight(biome).
                        double runningNoise   = 0;
                        double floorNumerator = 0;
                        // Floor is accumulated separately from noise. It is only applied if
                        // EVERY biome in the blend neighborhood defines terrain.sampler-floor
                        // (or receives the pack-level fallback). Any missing floor sampler
                        // cancels the floor at this sparse point (stored as NEGATIVE_INFINITY),
                        // preventing border artifacts. With a pack-level fallback configured,
                        // allHaveFloor will always remain true.

                        for(int b = 0; b < blendMap.size; b++) {
                            BiomeNoiseSamplers s = blendMap.samplers[b];
                            double w = blendMap.weights[b];
                            runningNoise += s.base().getSample(seed, absoluteX, scaledY, absoluteZ) * w;
                            if(SamplerFloorFeature.ENABLED && blendMap.allHaveFloor) {
                                // densityFloor() is non-null for all entries when allHaveFloor is true
                                floorNumerator += s.densityFloor().getSample(seed, absoluteX, scaledY, absoluteZ) * w;
                            }
                        }

                        noise = runningNoise / blendMap.totalWeight;
                        if(SamplerFloorFeature.ENABLED) {
                            // The floor is stored as a raw blended value with no elevation component.
                            // Sampler3D will compare it against the final (3D + elevation) density at
                            // the actual block position, where elevation is exact rather than sparse.
                            floorValue = blendMap.allHaveFloor
                                ? (float) (floorNumerator / blendMap.totalWeight)
                                : Float.NEGATIVE_INFINITY;
                        }
                    }

                    noiseStorage[x][z][y] = (float) noise;
                    if(SamplerFloorFeature.ENABLED) {
                        floorStorage[x][z][y] = floorValue;
                    }

                    if(debugChunk && TerrainDebug.isTargetY(scaledY)) {
                        int absX = xOrigin + (x << 2);
                        int absZ = zOrigin + (z << 2);
                        String centerBiome = biomeColumn.get(scaledY).getID();
                        String floorStr = (SamplerFloorFeature.ENABLED && floorStorage != null)
                            ? String.format(" floor=%.6f", (double) floorStorage[x][z][y])
                            : "";
                        TerrainDebug.LOG.info(
                            "[CI] sparse ({},{},{}) centerBiome={} uniqueBlendBiomes={} totalWeight={} noise={} storedFloat={}{}",
                            absX, scaledY, absZ,
                            centerBiome,
                            blendMap.size,
                            String.format("%.4f", blendMap.totalWeight),
                            String.format("%.6f", noise),
                            String.format("%.6f", (double) noiseStorage[x][z][y]),
                            floorStr);
                    }

                    if(y == size - 1) {
                        noiseStorage[x][z][size] = (float) noise;
                        if(SamplerFloorFeature.ENABLED) {
                            floorStorage[x][z][size] = floorValue;
                        }
                    }
                }
            }
        }

        for(int x = 0; x < 4; x++) {
            for(int z = 0; z < 4; z++) {
                for(int y = 0; y < size; y++) {
                    interpGrid[x][y][z] = new Interpolator3(
                        noiseStorage[x][z][y],
                        noiseStorage[x + 1][z][y],
                        noiseStorage[x][z][y + 1],
                        noiseStorage[x + 1][z][y + 1],
                        noiseStorage[x][z + 1][y],
                        noiseStorage[x + 1][z + 1][y],
                        noiseStorage[x][z + 1][y + 1],
                        noiseStorage[x + 1][z + 1][y + 1]);
                }
            }
        }

        if(SamplerFloorFeature.ENABLED) {
            // Determine whether any sparse point carries a real floor value.
            // NEGATIVE_INFINITY sentinel means "no floor" at that point.
            boolean anyFloor = false;
            outer:
            for(int x = 0; x < 5; x++) {
                for(int z = 0; z < 5; z++) {
                    for(int y = 0; y <= size; y++) {
                        if(floorStorage[x][z][y] != Float.NEGATIVE_INFINITY) {
                            anyFloor = true;
                            break outer;
                        }
                    }
                }
            }

            if(anyFloor) {
                Interpolator3[][][] fGrid = new Interpolator3[4][size][4];
                for(int x = 0; x < 4; x++) {
                    for(int z = 0; z < 4; z++) {
                        for(int y = 0; y < size; y++) {
                            fGrid[x][y][z] = new Interpolator3(
                                floorStorage[x    ][z    ][y    ],
                                floorStorage[x + 1][z    ][y    ],
                                floorStorage[x    ][z    ][y + 1],
                                floorStorage[x + 1][z    ][y + 1],
                                floorStorage[x    ][z + 1][y    ],
                                floorStorage[x + 1][z + 1][y    ],
                                floorStorage[x    ][z + 1][y + 1],
                                floorStorage[x + 1][z + 1][y + 1]);
                        }
                    }
                }
                this.floorGrid = fGrid;
            } else {
                this.floorGrid = null;
            }
        } else {
            this.floorGrid = null;
        }

        if(debugChunk && SamplerFloorFeature.ENABLED) {
            TerrainDebug.LOG.info("[CI] chunk ({},{}) hasFloor={}", chunkX, chunkZ, this.floorGrid != null);
        }
    }

    private static int reRange(int value, int high) {
        return Math.max(Math.min(value, high), 0);
    }

    /**
     * Gets the noise at a pair of internal chunk coordinates.
     *
     * @param x The internal X coordinate (0-15).
     * @param z The internal Z coordinate (0-15).
     *
     * @return double - The interpolated noise at the coordinates.
     */
    public double getNoise(double x, double y, double z) {
        return interpGrid[reRange(((int) x) / 4, 3)][(Math.max(Math.min(((int) y), max), min) - min) / 4][reRange(((int) z) / 4,
            3)].trilerp(
            (x % 4) / 4, (y % 4) / 4, (z % 4) / 4);
    }

    public double getNoise(int x, int y, int z) {
        return interpGrid[x / 4][(y - min) / 4][z / 4].trilerp(
            (double) (x & 3) / 4, // x & 3 == x % 4
            (double) (y & 3) / 4,
            (double) (z & 3) / 4
        );
    }

    /**
     * Returns true if any sparse point in this chunk has a floor value configured.
     * When false, {@link #getFloor(int, int, int)} must not be called.
     * <p>
     * Always returns {@code false} when {@link SamplerFloorFeature#ENABLED} is {@code false},
     * allowing the JIT to eliminate every floor call site in the sampler's hot path.
     */
    public boolean hasFloor() {
        return SamplerFloorFeature.ENABLED && floorGrid != null;
    }

    /**
     * Gets the interpolated floor density at internal chunk coordinates.
     * Only valid when {@link #hasFloor()} returns true.
     * The returned value is the raw floor target for total density (3D + elevation);
     * callers should compare it against the final combined density, not against the 3D component alone.
     */
    public double getFloor(int x, int y, int z) {
        return floorGrid[x / 4][(y - min) / 4][z / 4].trilerp(
            (double) (x & 3) / 4,
            (double) (y & 3) / 4,
            (double) (z & 3) / 4
        );
    }

    public double getFloor(double x, double y, double z) {
        return floorGrid[reRange(((int) x) / 4, 3)][(Math.max(Math.min(((int) y), max), min) - min) / 4][reRange(((int) z) / 4,
            3)].trilerp(
            (x % 4) / 4, (y % 4) / 4, (z % 4) / 4);
    }

    // Reusable per-thread structure for the biome weight map built once per (x,z) center.
    // Capacity 64 exceeds the maximum possible blend positions (7×7 = 49).
    // Linear scan for accumulate() is correct and fast; unique biome count is typically 1-5.
    private static final class BiomeWeightMap {
        static final int CAPACITY = 64;
        final BiomeNoiseSamplers[] samplers = new BiomeNoiseSamplers[CAPACITY];
        final double[]             weights  = new double[CAPACITY];
        int     size;
        double  totalWeight;
        boolean allHaveFloor; // only meaningful when SamplerFloorFeature.ENABLED

        void reset() {
            size         = 0;
            totalWeight  = 0.0;
            allHaveFloor = true;
        }

        // Identity comparison is correct: BiomeNoiseSamplers objects are config singletons —
        // two columns with the same biome return the same BiomeNoiseSamplers instance.
        void accumulate(BiomeNoiseSamplers s, double weight) {
            for(int i = 0; i < size; i++) {
                if(samplers[i] == s) {
                    weights[i] += weight;
                    totalWeight += weight;
                    return;
                }
            }
            samplers[size] = s;
            weights[size]  = weight;
            size++;
            totalWeight += weight;
        }
    }
}
