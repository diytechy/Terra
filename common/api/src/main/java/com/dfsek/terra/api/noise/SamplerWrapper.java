package com.dfsek.terra.api.noise;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * Implemented by samplers that wrap an inner {@link Sampler} (e.g. for dimension adaptation).
 * Allows cross-addon access to the wrapped sampler without reflection.
 */
public interface SamplerWrapper {
    Sampler getSampler();
}
