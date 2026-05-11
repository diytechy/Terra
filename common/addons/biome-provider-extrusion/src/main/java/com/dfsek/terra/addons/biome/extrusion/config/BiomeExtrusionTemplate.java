package com.dfsek.terra.addons.biome.extrusion.config;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Description;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import java.util.List;

import com.dfsek.terra.addons.biome.extrusion.BiomeExtrusionProvider;
import com.dfsek.terra.addons.biome.extrusion.api.Extrusion;
import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.profiler.Profiler;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class BiomeExtrusionTemplate implements ObjectTemplate<BiomeProvider> {
    private final Profiler profiler;

    public BiomeExtrusionTemplate(Profiler profiler) {
        this.profiler = profiler;
    }

    @Value("provider")
    private @Meta BiomeProvider provider;

    @Value("resolution")
    @Default
    private @Meta int resolution = 4;

    @Value("y-resolution")
    @Default
    private @Meta int yResolution = -1;

    @Value("extrusions")
    private @Meta List<@Meta Extrusion> extrusions;

    @Value("blend.sampler")
    @Default
    @Description("Sampler for X/Z coordinate warping of extrusion boundaries, independent of the pipeline blend.")
    private @Meta Sampler xzBlendSampler = Sampler.zero();

    @Value("blend.amplitude")
    @Default
    @Description("Amplitude in blocks of the X/Z coordinate warp applied before extrusion evaluation.")
    private @Meta double xzBlendAmplitude = 0d;

    @Value("y-blend.sampler")
    @Default
    @Description("Sampler for Y coordinate warping of extrusion boundaries. Evaluated in 2D (X/Z plane); can use a different frequency and expression than the X/Z blend.")
    private @Meta Sampler yBlendSampler = Sampler.zero();

    @Value("y-blend.amplitude")
    @Default
    @Description("Amplitude in blocks of the Y coordinate warp. A value of 16 shifts vertical boundaries up/down by up to 16 blocks.")
    private @Meta double yBlendAmplitude = 0d;

    @Override
    public BiomeProvider get() {
        int effectiveYResolution = yResolution > 0 ? yResolution : resolution;
        return new BiomeExtrusionProvider(provider, extrusions, resolution, effectiveYResolution,
                                          xzBlendSampler, xzBlendAmplitude,
                                          yBlendSampler, yBlendAmplitude, profiler);
    }
}
