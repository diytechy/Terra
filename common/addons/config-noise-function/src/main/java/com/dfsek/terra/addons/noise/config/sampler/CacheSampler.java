package com.dfsek.terra.addons.noise.config.sampler;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * A direct-mapped, thread-local cache wrapper for a {@link Sampler}.
 * <p>
 * Uses parallel primitive arrays to avoid autoboxing and object allocation.
 * The hash function is designed so that all 256 coordinates within a single
 * 16x16 chunk map to distinct slots, guaranteeing zero within-chunk collisions
 * for integer coordinates (when exp >= 8 for 2D, exp >= 13 for 3D).
 * <p>
 * Cache size is controlled by the {@code exp} parameter: the cache holds
 * {@code 2^exp} slots. The mask is {@code (2^exp) - 1}.
 * <p>
 * Default sizes: 2D exp=8 (256 slots, ~8 KB/thread),
 * 3D exp=17 (131072 slots, ~5 MB/thread).
 * With {@code int} coordinate keys: 2D ~6 KB/thread, 3D ~3.5 MB/thread.
 * <p>
 * When {@code intCoordinates} is enabled, input coordinates are rounded to the
 * nearest integer (saturating at int32 bounds) before cache lookup and sampler
 * evaluation. This increases cache hit rate for fractional coordinates and
 * reduces memory by storing coordinate keys as int32 instead of double.
 */
public class CacheSampler implements Sampler {

    private final Sampler sampler;
    private final boolean intCoordinates;
    private final int cacheMask;
    private final ThreadLocal<DirectCache2D> cache2D;
    private final ThreadLocal<DirectCache3D> cache3D;
    private final ThreadLocal<IntDirectCache2D> intCache2D;
    private final ThreadLocal<IntDirectCache3D> intCache3D;

    public CacheSampler(Sampler sampler, int dimensions, boolean intCoordinates, int exp) {
        this.sampler = sampler;
        this.intCoordinates = intCoordinates;
        int size = 1 << exp;
        this.cacheMask = size - 1;
        if(dimensions == 2) {
            if(intCoordinates) {
                this.intCache2D = ThreadLocal.withInitial(() -> new IntDirectCache2D(size));
                this.cache2D = null;
            } else {
                this.cache2D = ThreadLocal.withInitial(() -> new DirectCache2D(size));
                this.intCache2D = null;
            }
            this.cache3D = null;
            this.intCache3D = null;
        } else {
            if(intCoordinates) {
                this.intCache3D = ThreadLocal.withInitial(() -> new IntDirectCache3D(size));
                this.cache3D = null;
            } else {
                this.cache3D = ThreadLocal.withInitial(() -> new DirectCache3D(size));
                this.intCache3D = null;
            }
            this.cache2D = null;
            this.intCache2D = null;
        }
    }

    private static int saturateToInt(double v) {
        long r = Math.round(v);
        if(r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if(r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) r;
    }

    @Override
    public double getSample(long seed, double x, double y) {
        if(intCoordinates) {
            int ix = saturateToInt(x);
            int iz = saturateToInt(y);

            // Skip cache when all key components are zero to guard against
            // false hits on zero-initialized slots.
            if(ix == 0 && iz == 0 && seed == 0L) {
                return sampler.getSample(seed, ix, iz);
            }

            IntDirectCache2D cache = intCache2D.get();

            int lo = (ix & 0xF) | ((iz & 0xF) << 4);
            int hi = (ix >> 4) ^ (iz >> 4) ^ (int) seed;
            int index = (lo | (hi << 8)) & cacheMask;

            if(cache.keyX[index] == ix && cache.keyZ[index] == iz && cache.keySeed[index] == seed) {
                return cache.values[index];
            }

            double value = sampler.getSample(seed, ix, iz);
            cache.keyX[index] = ix;
            cache.keyZ[index] = iz;
            cache.keySeed[index] = seed;
            cache.values[index] = value;
            return value;
        }

        // Skip cache when all key components are zero to guard against
        // false hits on zero-initialized slots.
        if((Double.doubleToRawLongBits(x) | Double.doubleToRawLongBits(y) | seed) == 0L) {
            return sampler.getSample(seed, x, y);
        }

        DirectCache2D cache = cache2D.get();

        // Bits 0-3: chunk-local x, bits 4-7: chunk-local z — guarantees
        // zero collisions within a single 16x16 chunk for integer coordinates
        // when exp >= 8. Bits 8+: chunk identity spread.
        int lo = ((int) x & 0xF) | (((int) y & 0xF) << 4);
        int hi = ((int) x >> 4) ^ ((int) y >> 4) ^ (int) seed;
        int index = (lo | (hi << 8)) & cacheMask;

        if(cache.keyX[index] == x && cache.keyZ[index] == y && cache.keySeed[index] == seed) {
            return cache.values[index];
        }

        double value = sampler.getSample(seed, x, y);
        cache.keyX[index] = x;
        cache.keyZ[index] = y;
        cache.keySeed[index] = seed;
        cache.values[index] = value;
        return value;
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        if(intCoordinates) {
            int ix = saturateToInt(x);
            int iy = saturateToInt(y);
            int iz = saturateToInt(z);

            // Skip cache when all key components are zero to guard against
            // false hits on zero-initialized slots.
            if(ix == 0 && iy == 0 && iz == 0 && seed == 0L) {
                return sampler.getSample(seed, ix, iy, iz);
            }

            IntDirectCache3D cache = intCache3D.get();

            // Bits 0-3: chunk-local x, bits 4-7: chunk-local z,
            // bits 8-16: y coordinate (9 bits, covers 512-block height range).
            int index = ((ix & 0xF) | ((iz & 0xF) << 4) | ((iy & 0x1FF) << 8)) & cacheMask;

            if(cache.keyX[index] == ix && cache.keyY[index] == iy && cache.keyZ[index] == iz && cache.keySeed[index] == seed) {
                return cache.values[index];
            }

            double value = sampler.getSample(seed, ix, iy, iz);
            cache.keyX[index] = ix;
            cache.keyY[index] = iy;
            cache.keyZ[index] = iz;
            cache.keySeed[index] = seed;
            cache.values[index] = value;
            return value;
        }

        // Skip cache when all key components are zero to guard against
        // false hits on zero-initialized slots.
        if((Double.doubleToRawLongBits(x) | Double.doubleToRawLongBits(y) | Double.doubleToRawLongBits(z) | seed) == 0L) {
            return sampler.getSample(seed, x, y, z);
        }

        DirectCache3D cache = cache3D.get();

        // Bits 0-3: chunk-local x, bits 4-7: chunk-local z,
        // bits 8-16: y coordinate (9 bits, covers 512-block height range).
        int index = (((int) x & 0xF) | (((int) z & 0xF) << 4) | (((int) y & 0x1FF) << 8)) & cacheMask;

        if(cache.keyX[index] == x && cache.keyY[index] == y && cache.keyZ[index] == z && cache.keySeed[index] == seed) {
            return cache.values[index];
        }

        double value = sampler.getSample(seed, x, y, z);
        cache.keyX[index] = x;
        cache.keyY[index] = y;
        cache.keyZ[index] = z;
        cache.keySeed[index] = seed;
        cache.values[index] = value;
        return value;
    }

    private static final class DirectCache2D {
        final double[] keyX;
        final double[] keyZ;
        final long[] keySeed;
        final double[] values;

        DirectCache2D(int size) {
            keyX = new double[size];
            keyZ = new double[size];
            keySeed = new long[size];
            values = new double[size];
        }
    }

    private static final class DirectCache3D {
        final double[] keyX;
        final double[] keyY;
        final double[] keyZ;
        final long[] keySeed;
        final double[] values;

        DirectCache3D(int size) {
            keyX = new double[size];
            keyY = new double[size];
            keyZ = new double[size];
            keySeed = new long[size];
            values = new double[size];
        }
    }

    private static final class IntDirectCache2D {
        final int[] keyX;
        final int[] keyZ;
        final long[] keySeed;
        final double[] values;

        IntDirectCache2D(int size) {
            keyX = new int[size];
            keyZ = new int[size];
            keySeed = new long[size];
            values = new double[size];
        }
    }

    private static final class IntDirectCache3D {
        final int[] keyX;
        final int[] keyY;
        final int[] keyZ;
        final long[] keySeed;
        final double[] values;

        IntDirectCache3D(int size) {
            keyX = new int[size];
            keyY = new int[size];
            keyZ = new int[size];
            keySeed = new long[size];
            values = new double[size];
        }
    }
}
