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
    @Description("A sampler used to warp the Y coordinate before extrusion evaluation, producing organic vertical biome boundaries.")
    private @Meta Sampler blendSampler = Sampler.zero();

    @Value("blend.amplitude")
    @Default
    @Description("Amplitude in blocks of the Y-coordinate warp. A value of 16 shifts boundaries up/down by up to 16 blocks.")
    private @Meta double blendAmplitude = 0d;

    @Override
    public BiomeProvider get() {
        int effectiveYResolution = yResolution > 0 ? yResolution : resolution;
        return new BiomeExtrusionProvider(provider, extrusions, resolution, effectiveYResolution,
                                          blendSampler, blendAmplitude, profiler);
    }
}
