package com.dfsek.terra.addons.noise.config.templates;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import org.jetbrains.annotations.ApiStatus.Experimental;

import com.dfsek.terra.addons.noise.config.sampler.CacheSampler;


@Experimental
public class CacheSamplerTemplate extends SamplerTemplate<CacheSampler> {
    @Value("sampler")
    @Default
    private Sampler sampler;

    @Value("int")
    @Default
    private boolean intCoordinates = false;

    /**
     * Cache size exponent. Cache holds 2^exp slots. -1 means use the dimension default:
     * 8 for 2D (256 slots, ~8 KB/thread) or 17 for 3D (131072 slots, ~5 MB/thread).
     * Valid user-supplied range: 0–20.
     */
    @Value("exp")
    @Default
    private int exp = -1;

    public CacheSamplerTemplate() {

    }

    @Override
    public Sampler get() {
        final int effectiveExp;
        if(exp == -1) {
            effectiveExp = getDimensions() == 2 ? 8 : 17;
        } else if(exp < 0 || exp > 20) {
            throw new IllegalArgumentException(
                "CacheSampler 'exp' must be between 0 and 20, got: " + exp);
        } else {
            effectiveExp = exp;
        }
        return new CacheSampler(sampler, getDimensions(), intCoordinates, effectiveExp);
    }
}
