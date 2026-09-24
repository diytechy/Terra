/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline.cache;

import java.util.Arrays;


/**
 * Thread-local context for caching sampler values during a single biome chunk generation.
 *
 * Holds flat double arrays indexed by chunk-local coordinates. One array per cached sampler slot.
 * Reused across multiple chunks on the same thread via {@link #reset(int, int, int)}.
 *
 * Coordinate mapping: worldX (block coordinates) → array index via `(worldX - blockOriginX) / resolution`
 */
public final class ChunkGenerationContext {
    private int blockOriginX;
    private int blockOriginZ;
    private int resolution;
    private final int arraySize;
    private final double[][] caches;

    /**
     * @param numSlots number of cached samplers
     * @param arraySize pipeline array size (side length of square array)
     */
    public ChunkGenerationContext(int numSlots, int arraySize) {
        this.arraySize = arraySize;
        this.caches = new double[numSlots][arraySize * arraySize];
        // Initialize all arrays with NaN
        for (double[] cache : caches) {
            Arrays.fill(cache, Double.NaN);
        }
        this.blockOriginX = Integer.MIN_VALUE;  // Invalid until reset
        this.blockOriginZ = Integer.MIN_VALUE;
        this.resolution = 1;
    }

    /**
     * Prepare context for a new chunk generation.
     * Resets cache arrays to NaN. Stores the block-coordinate origin.
     */
    private long seed;
    private boolean active;

    public void reset(int blockOriginX, int blockOriginZ, int resolution, long seed) {
        this.blockOriginX = blockOriginX;
        this.blockOriginZ = blockOriginZ;
        this.resolution = resolution;
        this.seed = seed;
        this.active = true;
        // Reset all cache arrays to NaN
        for (double[] cache : caches) {
            Arrays.fill(cache, Double.NaN);
        }
    }

    /**
     * Invalidate the context so all subsequent inBounds() checks fail.
     * Prevents stale cache hits between chunks.
     */
    public void invalidate() {
        this.blockOriginX = Integer.MIN_VALUE;
        this.active = false;
    }

    /**
     * Cache index for a sample at exactly ({@code x}, {@code z}) with {@code seed}, or -1 if that
     * point has no cache cell of its own. A cell is used only for points exactly on this chunk's
     * pipeline grid (integral coordinates, offset from the origin a non-negative multiple of the
     * resolution, inside the array) and only for the seed the context was reset with, so the cache is
     * pure memoization: a hit always returns what the wrapped sampler returns for that exact point.
     * <p>
     * The previous lookup truncated coordinates ({@code (int) x}) and divided offsets toward zero, so
     * off-grid points (domain warps, cellular lookups, points just before the origin) shared a cell
     * with a grid point and received whichever value was stored first: evaluation-order dependent.
     */
    public int exactIndex(double x, double z, long seed) {
        if(LEGACY_INDEX) return legacyIndex(x, z);
        if(!active || seed != this.seed) return -1;
        if(x != Math.rint(x) || z != Math.rint(z)) return -1;
        long dx = (long) x - blockOriginX;
        long dz = (long) z - blockOriginZ;
        if(dx < 0 || dz < 0 || dx % resolution != 0 || dz % resolution != 0) return -1;
        long lx = dx / resolution, lz = dz / resolution;
        if(lx >= arraySize || lz >= arraySize) return -1;
        return (int) (lx * arraySize + lz);
    }

    /**
     * Convert world block coordinates to flat array index.
     * Returns -1 if the coordinates are out of bounds.
     */
    /**
     * -Dterra.pipeline.legacyCacheIndex=true restores the old, order-dependent lookup (truncating,
     * seed-blind). Diagnostics only: for before/after comparisons within one build.
     */
    private static final boolean LEGACY_INDEX = Boolean.getBoolean("terra.pipeline.legacyCacheIndex");

    private int legacyIndex(double x, double z) {
        if(!active) return -1;
        int lx = ((int) x - blockOriginX) / resolution;
        int lz = ((int) z - blockOriginZ) / resolution;
        if(lx < 0 || lx >= arraySize || lz < 0 || lz >= arraySize) return -1;
        return lx * arraySize + lz;
    }

    public boolean isActive() {
        return active;
    }

    /** True if (x, z) falls inside this chunk's cache area (on or off the grid). Diagnostics only. */
    public boolean inArea(double x, double z) {
        double lx = (x - blockOriginX) / resolution, lz = (z - blockOriginZ) / resolution;
        return lx >= 0 && lx < arraySize && lz >= 0 && lz < arraySize;
    }

    public double[] getCache(int slot) {
        return caches[slot];
    }
}
