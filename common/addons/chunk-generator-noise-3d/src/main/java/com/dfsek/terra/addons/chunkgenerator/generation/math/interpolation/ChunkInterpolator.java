/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation;

import com.dfsek.seismic.type.sampler.Sampler;

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
    private final Interpolator3[][][] floorGrid; // null when no sparse point carries a floor value
    private final boolean hasFloor;

    private final int min;
    private final int max;

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

        int range = this.max - this.min + 1;

        int size = range >> 2;

        interpGrid = new Interpolator3[4][size][4];

        // Sparse storage uses float (32-bit) to halve memory vs double.
        // Interpolator3 accepts double; Java auto-widens float at the call site.
        float[][][] noiseStorage = new float[5][5][size + 1];
        // Float.NEGATIVE_INFINITY is the sentinel meaning "no floor defined at this sparse point".
        float[][][] floorStorage = new float[5][5][size + 1];

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

        for(int x = 0; x < 5; x++) {
            int scaledX = x << 2;
            int absoluteX = xOrigin + scaledX;
            for(int z = 0; z < 5; z++) {
                int scaledZ = z << 2;
                int absoluteZ = zOrigin + scaledZ;

                Column<Biome> biomeColumn = centerColumns[x * 5 + z];

                for(int y = 0; y < size; y++) {
                    int scaledY = (y << 2) + min;
                    BiomeNoiseProperties generationSettings = biomeColumn.get(scaledY)
                        .getContext()
                        .get(noisePropertiesKey);

                    int step = generationSettings.samplers().blendStep();
                    int blend = generationSettings.samplers().blendDistance();

                    double noise;
                    float floorValue;

                    if(blend == 0 || scaledY < blendMinY || scaledY > blendMaxY) {
                        // Blend disabled: either the biome has blendDistance=0, or this Y level is
                        // outside the pack-configured blend range. Use center sample directly.
                        noise = generationSettings.noiseHolder().getNoise(generationSettings.samplers().base(), absoluteX, scaledY, absoluteZ, seed);
                        Sampler floor = generationSettings.samplers().densityFloor();
                        floorValue = (floor != null)
                            ? (float) floor.getSample(seed, absoluteX, scaledY, absoluteZ)
                            : Float.NEGATIVE_INFINITY;
                    } else {
                        // Option 4: Single-pass fetch + homogeneity check.
                        // Fetch all blend columns (lazily cached for subsequent y-levels) while
                        // simultaneously checking whether all neighbors share the center biome.
                        // If homogeneous, one noise evaluation replaces the full blend loop.
                        Biome centerBiome = biomeColumn.get(scaledY);
                        boolean homogeneous = true;

                        for(int xi = -blend; xi <= blend; xi++) {
                            for(int zi = -blend; zi <= blend; zi++) {
                                int blendX = xi * step;
                                int blendZ = zi * step;
                                int localIndex = (scaledX + localMaxBlend + blendX) + localMaxBlendAndChunk * (scaledZ + localMaxBlend + blendZ);
                                if(columns[localIndex] == null) {
                                    columns[localIndex] = provider.getColumn(absoluteX + blendX, absoluteZ + blendZ, seed, min, max);
                                }
                                // Track homogeneity but do NOT break early — remaining null columns
                                // must still be fetched for future y-level iterations.
                                if(homogeneous && columns[localIndex].get(scaledY) != centerBiome) {
                                    homogeneous = false;
                                }
                            }
                        }

                        if(homogeneous) {
                            // All neighbors are the same biome: blending is a weighted average of
                            // identical values, so the result equals the center sample directly.
                            noise = generationSettings.noiseHolder().getNoise(generationSettings.samplers().base(), absoluteX, scaledY, absoluteZ, seed);
                            Sampler floor = generationSettings.samplers().densityFloor();
                            floorValue = (floor != null)
                                ? (float) floor.getSample(seed, absoluteX, scaledY, absoluteZ)
                                : Float.NEGATIVE_INFINITY;
                        } else {
                            // Heterogeneous blend zone: all columns already fetched above,
                            // evaluate noise for each and compute weighted average.
                            double runningNoise = 0;
                            double runningDiv = 0;
                            // Floor is accumulated separately from noise. It is only applied if
                            // EVERY biome in the blend neighborhood defines terrain.sampler-floor
                            // (or receives the pack-level fallback). Any missing floor sampler
                            // cancels the floor at this sparse point (stored as NEGATIVE_INFINITY),
                            // preventing border artifacts. With a pack-level fallback configured,
                            // allHaveFloor will always remain true.
                            boolean allHaveFloor = true;
                            double floorNumerator = 0;

                            for(int xi = -blend; xi <= blend; xi++) {
                                for(int zi = -blend; zi <= blend; zi++) {
                                    int blendX = xi * step;
                                    int blendZ = zi * step;
                                    int localIndex = (scaledX + localMaxBlend + blendX) + localMaxBlendAndChunk * (scaledZ + localMaxBlend + blendZ);
                                    BiomeNoiseProperties properties = columns[localIndex]
                                        .get(scaledY)
                                        .getContext()
                                        .get(noisePropertiesKey);
                                    BiomeNoiseSamplers samplers = properties.samplers();
                                    double sample = properties.noiseHolder().getNoise(samplers.base(), absoluteX, scaledY, absoluteZ, seed);
                                    double weight = samplers.blendWeight();
                                    runningNoise += sample * weight;
                                    runningDiv += weight;
                                    Sampler floorSampler = samplers.densityFloor();
                                    if(floorSampler != null) {
                                        floorNumerator += floorSampler.getSample(seed, absoluteX, scaledY, absoluteZ) * weight;
                                    } else {
                                        allHaveFloor = false;
                                    }
                                }
                            }

                            noise = runningNoise / runningDiv;
                            // The floor is stored as a raw blended value with no elevation component.
                            // Sampler3D will compare it against the final (3D + elevation) density at
                            // the actual block position, where elevation is exact rather than sparse.
                            floorValue = allHaveFloor
                                ? (float) (floorNumerator / runningDiv)
                                : Float.NEGATIVE_INFINITY;
                        }
                    }

                    noiseStorage[x][z][y] = (float) noise;
                    floorStorage[x][z][y] = floorValue;
                    if(y == size - 1) {
                        noiseStorage[x][z][size] = (float) noise;
                        floorStorage[x][z][size] = floorValue;
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
        this.hasFloor = anyFloor;

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
     */
    public boolean hasFloor() {
        return floorGrid != null;
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
}
