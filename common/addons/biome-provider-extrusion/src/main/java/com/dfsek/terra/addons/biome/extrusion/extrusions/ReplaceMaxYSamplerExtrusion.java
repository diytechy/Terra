package com.dfsek.terra.addons.biome.extrusion.extrusions;

import com.dfsek.seismic.type.sampler.Sampler;

import java.util.Collection;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.dfsek.terra.addons.biome.extrusion.api.Extrusion;
import com.dfsek.terra.addons.biome.extrusion.api.ReplaceableBiome;
import com.dfsek.terra.addons.biome.query.api.BiomeQueries;
import com.dfsek.terra.api.util.collection.ProbabilityCollection;
import com.dfsek.terra.api.util.collection.TriStateIntCache;
import com.dfsek.terra.api.world.biome.Biome;


/**
 * Replaces biomes with a static minimum Y and a sampler-based maximum Y.
 */
public class ReplaceMaxYSamplerExtrusion implements Extrusion {
    private final Sampler sampler;
    private final int minY;
    private final Sampler maxYSampler;
    private final ProbabilityCollection<ReplaceableBiome> biomes;
    private final String tag;
    private final Predicate<Biome> hasTag;
    private final TriStateIntCache cache;

    public ReplaceMaxYSamplerExtrusion(Sampler sampler, int minY, Sampler maxYSampler, ProbabilityCollection<ReplaceableBiome> biomes, String tag) {
        this.sampler = sampler;
        this.minY = minY;
        this.maxYSampler = maxYSampler;
        this.biomes = biomes;
        this.tag = tag;
        this.hasTag = BiomeQueries.has(tag);
        this.cache = new TriStateIntCache(Biome.INT_ID_COUNTER.get());
    }

    public String getTag() {
        return tag;
    }

    @Override
    public Biome extrude(Biome original, int x, int y, int z, long seed) {
        int id = original.getIntID();

        long state = cache.get(id);
        boolean passes;

        if(state == TriStateIntCache.STATE_UNSET) {
            passes = hasTag.test(original);
            cache.set(id, passes);
        } else {
            passes = (state == TriStateIntCache.STATE_TRUE);
        }

        if(passes) {
            if(y >= minY) {
                double maxYValue = maxYSampler.getSample(seed, (double) x, (double) z);
                if(y <= maxYValue) {
                    return biomes.get(sampler, x, y, z, seed).get(original);
                }
            }
        }

        return original;
    }

    @Override
    public Collection<Biome> getBiomes() {
        return biomes
            .getContents()
            .stream()
            .filter(Predicate.not(ReplaceableBiome::isSelf))
            .map(ReplaceableBiome::get)
            .collect(Collectors.toSet());
    }
}
