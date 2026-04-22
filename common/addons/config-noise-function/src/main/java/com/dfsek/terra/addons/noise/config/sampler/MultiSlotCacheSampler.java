package com.dfsek.terra.addons.noise.config.sampler;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * Thread-local N-slot round-robin cache for sampler results.
 * <p>
 * Stores the N most-recently-evaluated (seed, x, z) → result mappings per thread.
 * Unlike {@link LastValueSampler} (1 slot), this handles non-consecutive revisits
 * to the same coordinate — specifically the pattern where a CellularSampler
 * NoiseLookup is evaluated at multiple distinct cell centers across a chunk,
 * and the pipeline cycles back to a previously-seen center.
 * <p>
 * Cache key: exact double bit patterns of (x, z) plus seed. Since cell centers
 * are computed deterministically from integer grid inputs, the same center always
 * produces identical double values.
 * <p>
 * Eviction: round-robin (FIFO). Slot count must be a power of 2.
 * Memory: ~280 bytes per instance per thread (8 slots × 4 fields).
 */
public class MultiSlotCacheSampler implements Sampler {
    private static final int SLOTS = 8;
    private static final int MASK = SLOTS - 1;

    private final Sampler delegate;
    private final ThreadLocal<SlotCache> cache = ThreadLocal.withInitial(SlotCache::new);

    public MultiSlotCacheSampler(Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public double getSample(long seed, double x, double y) {
        SlotCache sc = cache.get();
        long xBits = Double.doubleToRawLongBits(x);
        long zBits = Double.doubleToRawLongBits(y);

        for(int i = 0; i < SLOTS; i++) {
            if(sc.valid[i] && sc.seed[i] == seed && sc.x[i] == xBits && sc.z[i] == zBits) {
                return sc.result[i];
            }
        }

        double result = delegate.getSample(seed, x, y);
        int slot = sc.next;
        sc.seed[slot] = seed;
        sc.x[slot] = xBits;
        sc.z[slot] = zBits;
        sc.result[slot] = result;
        sc.valid[slot] = true;
        sc.next = (slot + 1) & MASK;
        return result;
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        return delegate.getSample(seed, x, y, z);
    }

    private static final class SlotCache {
        final long[] seed = new long[SLOTS];
        final long[] x = new long[SLOTS];
        final long[] z = new long[SLOTS];
        final double[] result = new double[SLOTS];
        final boolean[] valid = new boolean[SLOTS];
        int next = 0;
    }
}
