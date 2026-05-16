package com.dfsek.terra.api.noise;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * Implemented by samplers with a swappable delegate.
 * Allows cross-addon installation of cache wrappers without reflection.
 */
public interface DelegateSampler {
    Sampler getDelegate();
    void setDelegate(Sampler delegate);
}
