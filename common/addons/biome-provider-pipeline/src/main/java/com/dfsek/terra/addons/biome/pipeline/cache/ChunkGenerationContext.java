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
    public void reset(int blockOriginX, int blockOriginZ, int resolution) {
        this.blockOriginX = blockOriginX;
        this.blockOriginZ = blockOriginZ;
        this.resolution = resolution;
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
    }

    /**
     * Convert world block coordinates to flat array index.
     * Returns -1 if the coordinates are out of bounds.
     */
    public int toIndex(int worldX, int worldZ) {
        int lx = (worldX - blockOriginX) / resolution;
        int lz = (worldZ - blockOriginZ) / resolution;
        if (!inBounds(worldX, worldZ)) return -1;
        return lx * arraySize + lz;
    }

    /**
     * Check if world coordinates fall within the active grid bounds.
     */
    public boolean inBounds(int worldX, int worldZ) {
        int lx = (worldX - blockOriginX) / resolution;
        int lz = (worldZ - blockOriginZ) / resolution;
        return lx >= 0 && lx < arraySize && lz >= 0 && lz < arraySize;
    }

    /**
     * Get the cache array for a given sampler slot.
     */
    public double[] getCache(int slot) {
        return caches[slot];
    }
}
