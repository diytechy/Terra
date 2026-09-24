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

        // Only exact pipeline-grid points with the context's seed have a cache cell
        int idx = ctx.exactIndex(x, y, seed);
        if (STATS) stats(ctx, x, y, seed, idx);
        if (idx < 0) {
            return delegate.getSample(seed, x, y);
        }
        double[] cache = ctx.getCache(slot);
        double cached = cache[idx];

        // Cache hit
        if (!Double.isNaN(cached)) {
            if (AUDIT) audit(seed, x, y, cached);
            return cached;
        }

        // Cache miss — evaluate, store, and return
        double value = delegate.getSample(seed, x, y);
        cache[idx] = value;
        return value;
    }

    // -Dterra.pipeline.cacheAudit=true: on every cache hit, recompute and log values that differ
    // from the wrapped sampler at the exact requested point (diagnostics only; slow).
    private static final boolean AUDIT = Boolean.getBoolean("terra.pipeline.cacheAudit");
    public static final java.util.concurrent.atomic.AtomicLong AUDIT_HITS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong AUDIT_WRONG = new java.util.concurrent.atomic.AtomicLong();

    // -Dterra.pipeline.cacheStats=true: classify every call made while a context is active.
    private static final boolean STATS = Boolean.getBoolean("terra.pipeline.cacheStats");
    public static final java.util.concurrent.atomic.AtomicLongArray STAT = new java.util.concurrent.atomic.AtomicLongArray(4);
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> OFFGRID =
        new java.util.concurrent.ConcurrentHashMap<>();
    static {
        if (STATS) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[pipeline-cache-stats] exactGrid=" + STAT.get(0) + " offGridInArea=" + STAT.get(1)
                + " outsideArea=" + STAT.get(2) + " inactive=" + STAT.get(3));
            OFFGRID.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get())).limit(12)
                .forEach(e -> System.out.println("[pipeline-cache-stats] offGrid " + e.getKey() + " " + e.getValue()));
        }));
    }

    private void stats(ChunkGenerationContext ctx, double x, double y, long seed, int idx) {
        int cat;
        if (idx >= 0) cat = 0;
        else if (!ctx.isActive()) cat = 3;
        else if (ctx.inArea(x, y)) cat = 1;
        else cat = 2;
        STAT.incrementAndGet(cat);
        if (cat == 1) {
            String k = "slot" + slot + (x == Math.rint(x) && y == Math.rint(y) ? " integral-offgrid" : " fractional");
            OFFGRID.computeIfAbsent(k, q -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        }
    }

    private void audit(long seed, double x, double y, double cached) {
        long hits = AUDIT_HITS.incrementAndGet();
        double truth = delegate.getSample(seed, x, y);
        if (Double.compare(truth, cached) != 0) {
            long wrong = AUDIT_WRONG.incrementAndGet();
            if (wrong <= 20 || Long.bitCount(wrong) == 1) {
                org.slf4j.LoggerFactory.getLogger(ChunkScopedCacheSampler.class).info(
                    "[pipeline-cache-audit] slot {} at ({}, {}): cached {} but sampler gives {} (wrong {} of {} hits)",
                    slot, x, y, cached, truth, wrong, hits);
            }
        }
    }

    @Override
    public double getSample(long seed, double x, double y, double z) {
        // Pipeline is 2D; 3D calls always delegate
        return delegate.getSample(seed, x, y, z);
    }
}
