/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.generation;


import java.util.List;

import com.dfsek.seismic.type.sampler.Sampler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.addons.chunkgenerator.generation.math.SlantCalculationMethod;
import com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation.LazilyEvaluatedInterpolator;
import com.dfsek.terra.addons.chunkgenerator.generation.math.samplers.Sampler3D;
import com.dfsek.terra.addons.chunkgenerator.generation.math.samplers.SamplerProvider;
import com.dfsek.terra.addons.chunkgenerator.palette.BiomePaletteInfo;
import com.dfsek.terra.addons.chunkgenerator.palette.slant.SlantHolder;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.util.Column;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.ProtoChunk;
import com.dfsek.terra.api.world.chunk.generation.util.Palette;
import com.dfsek.terra.api.world.info.WorldProperties;


public class NoiseChunkGenerator3D implements ChunkGenerator {
    private final Platform platform;

    private final SamplerProvider samplerCache;

    private final BlockState air;

    private final int carverHorizontalResolution;
    private final int carverVerticalResolution;

    private final PropertyKey<BiomePaletteInfo> paletteInfoPropertyKey;
    private final PropertyKey<BiomeNoiseProperties> noisePropertiesKey;

    private final SlantCalculationMethod slantCalculationMethod;

    private final boolean useSlantPalettes;

    private final @Nullable Sampler minDensitySampler;
    private final boolean minDensitySmooth;
    private final double minDensitySmoothK;
    private final List<String> minDensitySkipTags;

    public NoiseChunkGenerator3D(ConfigPack pack, Platform platform, int elevationBlend, int carverHorizontalResolution,
                                 int carverVerticalResolution,
                                 PropertyKey<BiomeNoiseProperties> noisePropertiesKey,
                                 PropertyKey<BiomePaletteInfo> paletteInfoPropertyKey,
                                 SlantCalculationMethod slantCalculationMethod, boolean useSlantPalettes,
                                 int blendMinY, int blendMaxY,
                                 @Nullable Sampler minDensitySampler, boolean minDensitySmooth,
                                 double minDensitySmoothK, List<String> minDensitySkipTags) {
        this.platform = platform;
        this.air = platform.getWorldHandle().air();
        this.carverHorizontalResolution = carverHorizontalResolution;
        this.carverVerticalResolution = carverVerticalResolution;
        this.paletteInfoPropertyKey = paletteInfoPropertyKey;
        this.noisePropertiesKey = noisePropertiesKey;
        this.slantCalculationMethod = slantCalculationMethod;
        this.useSlantPalettes = useSlantPalettes;
        this.minDensitySampler = minDensitySampler;
        this.minDensitySmooth = minDensitySmooth;
        this.minDensitySmoothK = minDensitySmoothK;
        this.minDensitySkipTags = minDensitySkipTags;
        int maxBlend = pack
            .getBiomeProvider()
            .stream()
            .map(biome -> biome.getContext().get(noisePropertiesKey))
            .mapToInt(properties -> properties.blendDistance() * properties.blendStep())
            .max()
            .orElse(0);

        this.samplerCache = new SamplerProvider(platform, elevationBlend, noisePropertiesKey, maxBlend, blendMinY, blendMaxY);
    }

    private static double smoothMax(double a, double b, double k) {
        double ka = k * a, kb = k * b;
        double m = Math.max(ka, kb);
        return (m + Math.log(Math.exp(ka - m) + Math.exp(kb - m))) / k;
    }

    private Palette paletteAt(int x, int y, int z, Sampler3D sampler, BiomePaletteInfo paletteInfo, int depth) {
        SlantHolder slantHolder = paletteInfo.slantHolder();
        if(useSlantPalettes && slantHolder.isAboveDepth(depth)) {
            double slant = slantCalculationMethod.slant(sampler, x, y, z);
            if(slantHolder.isInSlantThreshold(slant)) {
                return slantHolder.getPalette(slant).getPalette(y);
            }
        }
        return paletteInfo.paletteHolder().getPalette(y);
    }

    @Override
    @SuppressWarnings("try")
    public void generateChunkData(@NotNull ProtoChunk chunk, @NotNull WorldProperties world,
                                  @NotNull BiomeProvider biomeProvider,
                                  int chunkX, int chunkZ) {
        platform.getProfiler().push("chunk_base_3d");
        int xOrig = (chunkX << 4);
        int zOrig = (chunkZ << 4);

        platform.getProfiler().push("sampler_cache");
        Sampler3D sampler = samplerCache.getChunk(chunkX, chunkZ, world, biomeProvider);
        platform.getProfiler().pop("sampler_cache");

        long seed = world.getSeed();

        platform.getProfiler().push("carver_init");
        LazilyEvaluatedInterpolator carver = new LazilyEvaluatedInterpolator(biomeProvider,
            chunkX,
            chunkZ,
            world.getMaxHeight(),
            noisePropertiesKey, world.getMinHeight(),
            carverHorizontalResolution,
            carverVerticalResolution,
            seed);
        platform.getProfiler().pop("carver_init");

        platform.getProfiler().push("block_placement");
        for(int x = 0; x < 16; x++) {
            for(int z = 0; z < 16; z++) {
                int paletteLevel = 0;

                int cx = xOrig + x;
                int cz = zOrig + z;

                BlockState data;
                Column<Biome> biomeColumn = biomeProvider.getColumn(cx, cz, world);
                Biome lastSeaBiome = null;
                int computedSea = 0;
                Biome lastMinDensityBiome = null;
                boolean skipMinDensity = false;
                for(int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
                    Biome biome = biomeColumn.get(y);

                    BiomePaletteInfo paletteInfo = biome.getContext().get(paletteInfoPropertyKey);

                    if(biome != lastSeaBiome) {
                        Sampler slSampler = paletteInfo.seaLevelSampler();
                        computedSea = slSampler != null
                            ? (int) Math.round(slSampler.getSample(seed, cx, cz))
                            : paletteInfo.seaLevel();
                        lastSeaBiome = biome;
                    }
                    int sea = computedSea;
                    Palette seaPalette = paletteInfo.ocean();

                    double density = sampler.sample(x, y, z);

                    if(minDensitySampler != null) {
                        if(biome != lastMinDensityBiome) {
                            skipMinDensity = !minDensitySkipTags.isEmpty()
                                && biome.getTags().stream().anyMatch(minDensitySkipTags::contains);
                            lastMinDensityBiome = biome;
                        }
                        if(!skipMinDensity) {
                            double floor = minDensitySampler.getSample(seed, cx, y, cz);
                            if(minDensitySmooth) {
                                double gap = Math.abs(density - floor);
                                density = gap > 4.0 / minDensitySmoothK
                                    ? Math.max(density, floor)
                                    : smoothMax(density, floor, minDensitySmoothK);
                            } else {
                                density = Math.max(density, floor);
                            }
                        }
                    }

                    if(density > 0) {
                        if(carver.sample(x, y, z) <= 0) {
                            data = paletteAt(x, y, z, sampler, paletteInfo, paletteLevel)
                                .get(paletteLevel, cx, y, cz, seed);
                            chunk.setBlock(x, y, z, data);
                            paletteLevel++;
                        } else if(paletteInfo.updatePaletteWhenCarving()) {
                            paletteLevel = 0;
                        } else {
                            paletteLevel++;
                        }
                    } else if(y <= sea) {
                        chunk.setBlock(x, y, z, seaPalette.get(sea - y, x + xOrig, y, z + zOrig, seed));
                        paletteLevel = 0;
                    } else {
                        paletteLevel = 0;
                    }
                }
            }
        }
        platform.getProfiler().pop("block_placement");
        platform.getProfiler().pop("chunk_base_3d");
    }

    @Override
    public BlockState getBlock(WorldProperties world, int x, int y, int z, BiomeProvider biomeProvider) {
        Biome biome = biomeProvider.getBiome(x, y, z, world.getSeed());
        Sampler3D sampler = samplerCache.get(x, z, world, biomeProvider);

        BiomePaletteInfo paletteInfo = biome.getContext().get(paletteInfoPropertyKey);

        int fdX = Math.floorMod(x, 16);
        int fdZ = Math.floorMod(z, 16);

        Sampler slSampler = paletteInfo.seaLevelSampler();
        int sea = slSampler != null
            ? (int) Math.round(slSampler.getSample(world.getSeed(), x, z))
            : paletteInfo.seaLevel();

        Palette palette = paletteAt(fdX, y, fdZ, sampler, paletteInfo, 0);
        double noise = sampler.sample(fdX, y, fdZ);
        if(minDensitySampler != null) {
            boolean skip = !minDensitySkipTags.isEmpty()
                && biome.getTags().stream().anyMatch(minDensitySkipTags::contains);
            if(!skip) {
                double floor = minDensitySampler.getSample(world.getSeed(), x, y, z);
                if(minDensitySmooth) {
                    double gap = Math.abs(noise - floor);
                    noise = gap > 4.0 / minDensitySmoothK
                        ? Math.max(noise, floor)
                        : smoothMax(noise, floor, minDensitySmoothK);
                } else {
                    noise = Math.max(noise, floor);
                }
            }
        }
        if(noise > 0) {
            int level = 0;
            for(int yi = world.getMaxHeight() - 1; yi > y; yi--) {
                if(sampler.sample(fdX, yi, fdZ) > 0) level++;
                else level = 0;
            }
            return palette.get(level, x, y, z, world.getSeed());
        } else if(y <= sea) {
            return paletteInfo.ocean().get(sea - y, x, y, z, world.getSeed());
        } else return air;
    }

    @Override
    public Palette getPalette(int x, int y, int z, WorldProperties world, BiomeProvider biomeProvider) {
        return biomeProvider.getBiome(x, y, z, world.getSeed()).getContext().get(paletteInfoPropertyKey).paletteHolder().getPalette(y);
    }

    public double getSlant(int x, int y, int z, WorldProperties world, BiomeProvider biomeProvider) {
        int fdX = Math.floorMod(x, 16);
        int fdZ = Math.floorMod(z, 16);
        Sampler3D sampler = samplerCache.get(x, z, world, biomeProvider);
        return slantCalculationMethod.slant(sampler, fdX, y, fdZ);
    }

    public SamplerProvider samplerProvider() {
        return samplerCache;
    }
}
