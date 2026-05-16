/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline.cache;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * A sampler wrapper that caches results in a thread-local chunk-generation context.
 *
 * When a {@link ChunkGenerationContext} is active (during biome chunk generation),
 * this sampler stores and retrieves cached values indexed by chunk-local coordinates.
 * Outside a generation context, all calls are delegated directly to the wrapped sampler.
 *
 * Caching occurs only for 2D samples; 3D calls always delegate (pipeline is 2D).
 * Uses NaN sentinel in cache arrays to detect unfilled slots.
 */
public final class ChunkScopedCacheSampler implements Sampler {
    private final Sampler delegate;
    private final ThreadLocal<ChunkGenerationContext> contextRef;
    private final int slot;

    public ChunkScopedCacheSampler(Sampler delegate, ThreadLocal<ChunkGenerationContext> contextRef, int slot) {
        this.delegate = delegate;
        this.contextRef = contextRef;
        this.slot = slot;
    }

    @Override
    public double getSample(long seed, double x, double y) {
        ChunkGenerationContext ctx = contextRef.get();

        // No active context — delegate directly
        if (ctx == null) {
            return delegate.getSample(seed, x, y);
        }

        // Convert doubles to integer coordinates (worldX is already int-valued from viewPoint.worldX())
        int worldX = (int) x;
        int worldZ = (int) y;

        // Out of bounds — delegate directly
        if (!ctx.inBounds(worldX, worldZ)) {
            return delegate.getSample(seed, x, y);
        }

        // In bounds — check cache
        int idx = ctx.toIndex(worldX, worldZ);
        double[] cache = ctx.getCache(slot);
        double cached = cache[idx];

        // Cache hit
        if (!Double.isNaN(cached)) {
            return cached;
        }

        // Cache miss — evaluate, store, and return
        double value = delegate.getSample(seed, x, y);
        cache[idx] = value;
        return value;
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        // Pipeline is 2D; 3D calls always delegate
        return delegate.getSample(seed, x, y, z);
    }
}
