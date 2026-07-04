/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.noise.config.sampler;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * A single-entry thread-local cache that stores the most recent 2D sample result.
 * <p>
 * When the same sampler instance is shared across multiple evaluations at the same
 * coordinate (e.g. one pack sampler feeding several downstream expressions, biome-shape
 * lookups, and the elevation/interpolation grid within a single column), subsequent calls
 * with identical {@code (seed, x, z)} return the cached result without recomputing the
 * underlying noise/expression graph.
 * <p>
 * Only 2D calls are cached; 3D calls delegate directly to the underlying sampler. The cache
 * is a pure memoization keyed on the exact query, so output is byte-identical to the unwrapped
 * sampler. Per-thread state (~40 bytes) keeps it safe under parallel chunk generation.
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
        if(lv.valid && lv.seed == seed && lv.x == x && lv.z == y) {
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
