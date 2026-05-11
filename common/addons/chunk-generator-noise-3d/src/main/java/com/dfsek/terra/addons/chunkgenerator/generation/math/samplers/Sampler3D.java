/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation.math.samplers;

import com.dfsek.seismic.math.floatingpoint.FloatingPointFunctions;

import com.dfsek.terra.addons.chunkgenerator.SamplerFloorFeature;
import com.dfsek.terra.addons.chunkgenerator.TerrainDebug;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation.ChunkInterpolator;
import com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation.ElevationInterpolator;
import com.dfsek.terra.api.profiler.Profiler;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class Sampler3D {
    private final ChunkInterpolator interpolator;
    private final ElevationInterpolator elevationInterpolator;
    // TerrainDebug fields — all reads are JIT-eliminated when TerrainDebug.ENABLED = false.
    private final boolean debugChunk;
    private final int chunkX;
    private final int chunkZ;

    public Sampler3D(int x, int z, long seed, int minHeight, int maxHeight, BiomeProvider provider, int elevationSmooth,
                     PropertyKey<BiomeNoiseProperties> noisePropertiesKey, int maxBlend, int blendMinY, int blendMaxY,
                     Profiler profiler) {
        // x and z are chunk indices (e.g. 1, -21), not block origins.
        this.chunkX = x;
        this.chunkZ = z;
        this.debugChunk = TerrainDebug.ENABLED
            && TerrainDebug.isTargetChunk(x, z)
            && TerrainDebug.isTargetSeed(seed);

        long t0 = System.nanoTime();
        this.elevationInterpolator = new ElevationInterpolator(seed, x, z, provider, elevationSmooth, noisePropertiesKey);
        profiler.record("chunk_base_3d.sampler_cache.elevation_interpolator", System.nanoTime() - t0);

        t0 = System.nanoTime();
        this.interpolator = new ChunkInterpolator(seed, x, z, provider,
            minHeight, maxHeight, noisePropertiesKey, maxBlend, blendMinY, blendMaxY);
        profiler.record("chunk_base_3d.sampler_cache.chunk_interpolator", System.nanoTime() - t0);
    }

    public double sample(double x, double y, double z) {
        int rx = FloatingPointFunctions.round(x);
        int rz = FloatingPointFunctions.round(z);
        double noise3d = interpolator.getNoise(x, y, z);
        double elev    = elevationInterpolator.getElevation(rx, rz);
        double density = noise3d + elev;
        if(SamplerFloorFeature.ENABLED && interpolator.hasFloor()) {
            double floor = interpolator.getFloor(x, y, z);
            if(TerrainDebug.ENABLED && debugChunk) {
                int wy = FloatingPointFunctions.round(y);
                if(TerrainDebug.isTargetY(wy)) {
                    int wx = (chunkX << 4) + rx, wz = (chunkZ << 4) + rz;
                    if(wx == TerrainDebug.TARGET_WORLD_X && wz == TerrainDebug.TARGET_WORLD_Z) {
                        TerrainDebug.LOG.info(
                            "[S3D-d] ({},{},{}) noise3d={} elev={} preFloor={} floor={} final={}",
                            wx, wy, wz,
                            String.format("%.6f", noise3d),
                            String.format("%.6f", elev),
                            String.format("%.6f", density),
                            String.format("%.6f", floor),
                            String.format("%.6f", Math.max(density, floor)));
                    }
                }
            }
            density = Math.max(density, floor);
        } else if(TerrainDebug.ENABLED && debugChunk) {
            int wy = FloatingPointFunctions.round(y);
            if(TerrainDebug.isTargetY(wy)) {
                int wx = (chunkX << 4) + rx, wz = (chunkZ << 4) + rz;
                if(wx == TerrainDebug.TARGET_WORLD_X && wz == TerrainDebug.TARGET_WORLD_Z) {
                    TerrainDebug.LOG.info(
                        "[S3D-d] ({},{},{}) noise3d={} elev={} density={} (no floor)",
                        wx, wy, wz,
                        String.format("%.6f", noise3d),
                        String.format("%.6f", elev),
                        String.format("%.6f", density));
                }
            }
        }
        return density;
    }

    public double sample(int x, int y, int z) {
        // x and z are chunk-local (0–15); y is world-absolute.
        double noise3d = interpolator.getNoise(x, y, z);
        double elev    = elevationInterpolator.getElevation(x, z);
        double density = noise3d + elev;
        if(SamplerFloorFeature.ENABLED && interpolator.hasFloor()) {
            double floor = interpolator.getFloor(x, y, z);
            if(TerrainDebug.ENABLED && debugChunk) {
                if(TerrainDebug.isTargetY(y)) {
                    int wx = (chunkX << 4) + x, wz = (chunkZ << 4) + z;
                    if(wx == TerrainDebug.TARGET_WORLD_X && wz == TerrainDebug.TARGET_WORLD_Z) {
                        TerrainDebug.LOG.info(
                            "[S3D-i] ({},{},{}) noise3d={} elev={} preFloor={} floor={} final={}",
                            wx, y, wz,
                            String.format("%.6f", noise3d),
                            String.format("%.6f", elev),
                            String.format("%.6f", density),
                            String.format("%.6f", floor),
                            String.format("%.6f", Math.max(density, floor)));
                    }
                }
            }
            density = Math.max(density, floor);
        } else if(TerrainDebug.ENABLED && debugChunk) {
            if(TerrainDebug.isTargetY(y)) {
                int wx = (chunkX << 4) + x, wz = (chunkZ << 4) + z;
                if(wx == TerrainDebug.TARGET_WORLD_X && wz == TerrainDebug.TARGET_WORLD_Z) {
                    TerrainDebug.LOG.info(
                        "[S3D-i] ({},{},{}) noise3d={} elev={} density={} (no floor)",
                        wx, y, wz,
                        String.format("%.6f", noise3d),
                        String.format("%.6f", elev),
                        String.format("%.6f", density));
                }
            }
        }
        return density;
    }
}
