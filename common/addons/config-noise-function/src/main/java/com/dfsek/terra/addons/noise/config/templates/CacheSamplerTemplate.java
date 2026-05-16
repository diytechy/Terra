package com.dfsek.terra.addons.noise.config.templates;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Description;
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
     * 12 for 2D (4096 slots, 64x64) or 17 for 3D (131072 slots).
     * Valid user-supplied range: 0–20.
     */
    @Value("exp")
    @Default
    private int exp = -1;

    @Value("resolution")
    @Default
    @Description("""
                 The coordinate resolution for cache index hashing.

                 When the sampler is queried at a fixed stride (e.g., every 2 blocks
                 in the biome pipeline with resolution=2), setting this to match
                 ensures coordinates pack perfectly into distinct cache slots.
                 Default is 1 (no shift).""")
    private int resolution = 1;

    public CacheSamplerTemplate() {

    }

    @Override
    public Sampler get() {
        final int effectiveExp;
        if(exp == -1) {
            effectiveExp = getDimensions() == 2 ? 12 : 17;
        } else if(exp < 0 || exp > 20) {
            throw new IllegalArgumentException(
                "CacheSampler 'exp' must be between 0 and 20, got: " + exp);
        } else {
            effectiveExp = exp;
        }
        if(resolution < 1) {
            throw new IllegalArgumentException(
                "CacheSampler 'resolution' must be >= 1, got: " + resolution);
        }
        return new CacheSampler(sampler, getDimensions(), intCoordinates, effectiveExp, resolution);
    }
}
