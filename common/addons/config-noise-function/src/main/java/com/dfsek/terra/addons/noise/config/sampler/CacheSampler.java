package com.dfsek.terra.addons.noise.config.sampler;

import java.util.Arrays;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * A direct-mapped, thread-local cache wrapper for a {@link Sampler}.
 * <p>
 * Uses parallel primitive arrays to avoid autoboxing and object allocation.
 * The hash function uses a configurable resolution shift so that coordinates
 * queried at a given stride (e.g., every 2 blocks when resolution=2) pack
 * perfectly into distinct cache slots.
 * <p>
 * Cache size is controlled by the {@code exp} parameter: the cache holds
 * {@code 2^exp} slots. The mask is {@code (2^exp) - 1}.
 * <p>
 * Default sizes: 2D exp=12 (4096 slots, 64x64),
 * 3D exp=17 (131072 slots).
 * <p>
 * Since each cache is thread-local and per-sampler, the seed is constant
 * for the lifetime of the cache and is not stored. Empty slots are detected
 * via NaN-initialized values arrays, eliminating special-case guards.
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
    private final int resolutionShift;
    private final ThreadLocal<DirectCache2D> cache2D;
    private final ThreadLocal<DirectCache3D> cache3D;
    private final ThreadLocal<IntDirectCache2D> intCache2D;
    private final ThreadLocal<IntDirectCache3D> intCache3D;

    public CacheSampler(Sampler sampler, int dimensions, boolean intCoordinates, int exp, int resolution) {
        this.sampler = sampler;
        this.intCoordinates = intCoordinates;
        int size = 1 << exp;
        this.cacheMask = size - 1;
        this.resolutionShift = Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(1, resolution)));
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
            if(intCache2D == null) {
                return sampler.getSample(seed, x, y);
            }

            int ix = saturateToInt(x);
            int iz = saturateToInt(y);

            IntDirectCache2D cache = intCache2D.get();

            int sx = ix >> resolutionShift;
            int sz = iz >> resolutionShift;
            int lo = (sx & 0x3F) | ((sz & 0x3F) << 6);
            int hi = (sx >> 6) ^ (sz >> 6);
            int index = (lo | (hi << 12)) & cacheMask;

            double cached = cache.values[index];
            if(!Double.isNaN(cached) && cache.keyX[index] == ix && cache.keyZ[index] == iz) {
                return cached;
            }

            double value = sampler.getSample(seed, ix, iz);
            cache.keyX[index] = ix;
            cache.keyZ[index] = iz;
            cache.values[index] = value;
            return value;
        }

        if(cache2D == null) {
            return sampler.getSample(seed, x, y);
        }

        DirectCache2D cache = cache2D.get();

        int sx = (int) x >> resolutionShift;
        int sz = (int) y >> resolutionShift;
        int lo = (sx & 0x3F) | ((sz & 0x3F) << 6);
        int hi = (sx >> 6) ^ (sz >> 6);
        int index = (lo | (hi << 12)) & cacheMask;

        double cached = cache.values[index];
        if(!Double.isNaN(cached) && cache.keyX[index] == x && cache.keyZ[index] == y) {
            return cached;
        }

        double value = sampler.getSample(seed, x, y);
        cache.keyX[index] = x;
        cache.keyZ[index] = y;
        cache.values[index] = value;
        return value;
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        if(intCoordinates) {
            if(intCache3D == null) {
                return sampler.getSample(seed, x, y, z);
            }

            int ix = saturateToInt(x);
            int iy = saturateToInt(y);
            int iz = saturateToInt(z);

            IntDirectCache3D cache = intCache3D.get();

            int sx = ix >> resolutionShift;
            int sz = iz >> resolutionShift;
            int index = ((sx & 0xF) | ((sz & 0xF) << 4) | ((iy & 0x1FF) << 8)) & cacheMask;

            double cached = cache.values[index];
            if(!Double.isNaN(cached) && cache.keyX[index] == ix && cache.keyY[index] == iy && cache.keyZ[index] == iz) {
                return cached;
            }

            double value = sampler.getSample(seed, ix, iy, iz);
            cache.keyX[index] = ix;
            cache.keyY[index] = iy;
            cache.keyZ[index] = iz;
            cache.values[index] = value;
            return value;
        }

        if(cache3D == null) {
            return sampler.getSample(seed, x, y, z);
        }

        DirectCache3D cache = cache3D.get();

        int sx = (int) x >> resolutionShift;
        int sz = (int) z >> resolutionShift;
        int index = ((sx & 0xF) | ((sz & 0xF) << 4) | (((int) y & 0x1FF) << 8)) & cacheMask;

        double cached = cache.values[index];
        if(!Double.isNaN(cached) && cache.keyX[index] == x && cache.keyY[index] == y && cache.keyZ[index] == z) {
            return cached;
        }

        double value = sampler.getSample(seed, x, y, z);
        cache.keyX[index] = x;
        cache.keyY[index] = y;
        cache.keyZ[index] = z;
        cache.values[index] = value;
        return value;
    }

    private static final class DirectCache2D {
        final double[] keyX;
        final double[] keyZ;
        final double[] values;

        DirectCache2D(int size) {
            keyX = new double[size];
            keyZ = new double[size];
            values = new double[size];
            Arrays.fill(values, Double.NaN);
        }
    }

    private static final class DirectCache3D {
        final double[] keyX;
        final double[] keyY;
        final double[] keyZ;
        final double[] values;

        DirectCache3D(int size) {
            keyX = new double[size];
            keyY = new double[size];
            keyZ = new double[size];
            values = new double[size];
            Arrays.fill(values, Double.NaN);
        }
    }

    private static final class IntDirectCache2D {
        final int[] keyX;
        final int[] keyZ;
        final double[] values;

        IntDirectCache2D(int size) {
            keyX = new int[size];
            keyZ = new int[size];
            values = new double[size];
            Arrays.fill(values, Double.NaN);
        }
    }

    private static final class IntDirectCache3D {
        final int[] keyX;
        final int[] keyY;
        final int[] keyZ;
        final double[] values;

        IntDirectCache3D(int size) {
            keyX = new int[size];
            keyY = new int[size];
            keyZ = new int[size];
            values = new double[size];
            Arrays.fill(values, Double.NaN);
        }
    }
}
