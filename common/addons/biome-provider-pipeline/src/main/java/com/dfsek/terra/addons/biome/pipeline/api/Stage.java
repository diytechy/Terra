package com.dfsek.terra.addons.biome.pipeline.api;

import com.dfsek.seismic.type.sampler.Sampler;
import org.jetbrains.annotations.Nullable;

import com.dfsek.terra.addons.biome.pipeline.api.biome.PipelineBiome;
import com.dfsek.terra.addons.biome.pipeline.pipeline.BiomeChunkImpl;


public interface Stage {
    PipelineBiome apply(BiomeChunkImpl.ViewPoint viewPoint);

    int maxRelativeReadDistance();

    @Nullable
    default Sampler getSampler() {
        return null;
    }

    default Iterable<PipelineBiome> getBiomes(Iterable<PipelineBiome> biomes) {
        return biomes;
    }
}
