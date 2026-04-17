package com.dfsek.terra.addons.chunkgenerator.config.noise;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import com.dfsek.terra.api.config.meta.Meta;


public class BiomeNoiseConfigTemplate implements ObjectTemplate<BiomeNoiseProperties> {
    private static final Sampler NO_MIN_DENSITY_SAMPLER = Sampler.zero();
    private static final Sampler NO_FLOOR_SAMPLER = Sampler.zero();

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

    @Value("terrain.min-density.sampler")
    @Default
    private @Meta Sampler minDensitySampler = NO_MIN_DENSITY_SAMPLER;

    @Value("terrain.min-density.smooth")
    @Default
    private @Meta boolean minDensitySmooth = false;

    @Value("terrain.min-density.smooth-k")
    @Default
    private @Meta double minDensitySmoothK = 1.0;

    @Value("terrain.sampler-floor")
    @Default
    private @Meta Sampler densityFloor = NO_FLOOR_SAMPLER;

    public BiomeNoiseConfigTemplate(int defaultBlendDistance, int defaultBlendStep,
                                    double defaultBlendWeight, double defaultElevationWeight,
                                    @org.jetbrains.annotations.Nullable Sampler packDensityFloor) {
        this.blendDistance   = defaultBlendDistance;
        this.blendStep       = defaultBlendStep;
        this.blendWeight     = defaultBlendWeight;
        this.elevationWeight = defaultElevationWeight;
        // If the pack defines a fallback floor sampler, use it as the per-biome default so that
        // allHaveFloor stays true at blend borders. Biomes that define terrain.sampler-floor
        // in their own YAML will override this via the @Value/@Default Tectonic mechanism.
        if(packDensityFloor != null) {
            this.densityFloor = packDensityFloor;
        }
    }

    @Override
    public BiomeNoiseProperties get() {
        return new BiomeNoiseProperties(
            new BiomeNoiseSamplers(baseSampler, elevationSampler, carvingSampler, blendDistance, blendStep, blendWeight, elevationWeight,
                minDensitySampler == NO_MIN_DENSITY_SAMPLER ? null : minDensitySampler, minDensitySmooth, minDensitySmoothK,
                densityFloor == NO_FLOOR_SAMPLER ? null : densityFloor),
            new ThreadLocalNoiseHolder());
    }
}
