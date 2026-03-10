package com.dfsek.terra.addons.noise.config.sampler;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * A single-entry thread-local cache that stores the most recent 2D sample result.
 * <p>
 * When the same sampler instance is shared across multiple expression evaluations
 * at the same coordinate (e.g., during biome pipeline stage iteration), subsequent
 * calls with identical (seed, x, z) return the cached result without recomputation.
 * <p>
 * Only wraps 2D calls; 3D calls delegate directly to the underlying sampler.
 * Memory cost: ~32 bytes per thread (seed + x + z + result).
 */
public class LastValueSampler implements Sampler {

    private final Sampler delegate;
    private final ThreadLocal<LastValue> lastValue = ThreadLocal.withInitial(LastValue::new);

    public LastValueSampler(Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public double getSample(long seed, double x, double y) {
        LastValue lv = lastValue.get();
        if(lv.seed == seed && lv.x == x && lv.z == y && lv.valid) {
            return lv.result;
        }
        double result = delegate.getSample(seed, x, y);
        lv.seed = seed;
        lv.x = x;
        lv.z = y;
        lv.result = result;
        lv.valid = true;
        return result;
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        return delegate.getSample(seed, x, y, z);
    }

    public Sampler getDelegate() {
        return delegate;
    }

    private static final class LastValue {
        long seed;
        double x;
        double z;
        double result;
        boolean valid;
    }
}
