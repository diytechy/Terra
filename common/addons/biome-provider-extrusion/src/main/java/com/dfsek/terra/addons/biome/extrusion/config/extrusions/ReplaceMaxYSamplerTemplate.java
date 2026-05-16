package com.dfsek.terra.addons.biome.extrusion.config.extrusions;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Value;

import com.dfsek.terra.addons.biome.extrusion.api.Extrusion;
import com.dfsek.terra.addons.biome.extrusion.api.ReplaceableBiome;
import com.dfsek.terra.addons.biome.extrusion.extrusions.ReplaceMaxYSamplerExtrusion;
import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.util.collection.ProbabilityCollection;


public class ReplaceMaxYSamplerTemplate implements com.dfsek.tectonic.api.config.template.object.ObjectTemplate<Extrusion> {
    @Value("sampler")
    private @Meta Sampler sampler;

    @Value("min-y")
    private @Meta int minY;

    @Value("max-y-sampler")
    private @Meta Sampler maxYSampler;

    @Value("to")
    private @Meta ProbabilityCollection<@Meta ReplaceableBiome> biomes;

    @Value("from")
    private @Meta String fromTag;

    @Override
    public Extrusion get() {
        return new ReplaceMaxYSamplerExtrusion(sampler, minY, maxYSampler, biomes, fromTag);
    }
}
