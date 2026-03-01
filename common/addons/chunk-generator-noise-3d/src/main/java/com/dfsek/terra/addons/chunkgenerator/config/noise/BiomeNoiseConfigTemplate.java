package com.dfsek.terra.addons.chunkgenerator.config.noise;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import com.dfsek.terra.api.config.meta.Meta;


public class BiomeNoiseConfigTemplate implements ObjectTemplate<BiomeNoiseProperties> {
    @Value("terrain.sampler")
    private @Meta Sampler baseSampler;

    @Value("terrain.sampler-2d")
    @Default
    private @Meta Sampler elevationSampler = Sampler.zero();

    @Value("carving.sampler")
    @Default
    private @Meta Sampler carvingSampler = Sampler.zero();

    @Value("terrain.blend.distance")
    @Default
    private @Meta int blendDistance;

    @Value("terrain.blend.weight")
    @Default
    private @Meta double blendWeight;

    @Value("terrain.blend.step")
    @Default
    private @Meta int blendStep;

    @Value("terrain.blend.weight-2d")
    @Default
    private @Meta double elevationWeight;

    public BiomeNoiseConfigTemplate(int defaultBlendDistance, int defaultBlendStep,
                                    double defaultBlendWeight, double defaultElevationWeight) {
        this.blendDistance   = defaultBlendDistance;
        this.blendStep       = defaultBlendStep;
        this.blendWeight     = defaultBlendWeight;
        this.elevationWeight = defaultElevationWeight;
    }

    @Override
    public BiomeNoiseProperties get() {
        return new BiomeNoiseProperties(baseSampler, elevationSampler, carvingSampler, blendDistance, blendStep, blendWeight,
            elevationWeight, new ThreadLocalNoiseHolder());
    }
}
