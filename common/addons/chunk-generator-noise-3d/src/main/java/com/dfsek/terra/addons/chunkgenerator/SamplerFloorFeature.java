/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator;

/**
 * Compile-time feature flag for the sampler-floor subsystem.
 * <p>
 * When {@link #ENABLED} is {@code false}, all floor-related code paths in
 * {@code ChunkInterpolator}, {@code Sampler3D}, and the addon initializer are
 * short-circuited via {@code if(SamplerFloorFeature.ENABLED)} guards on a
 * {@code static final boolean}. This lets javac / the JIT perform dead-code
 * elimination so the feature has zero runtime memory or CPU cost — no
 * {@code floorStorage} or {@code floorGrid} arrays are allocated, no floor
 * samples are taken, and the per-block {@code hasFloor()} branch is removed
 * from the chunk sampler's inner loop.
 * <p>
 * Per-biome {@code terrain.sampler-floor} and pack-level {@code blend.sampler-floor}
 * YAML keys are still accepted by Tectonic (to avoid breaking pack validation),
 * but their values are ignored at generation time when this flag is off.
 * <p>
 * Flip to {@code false} for exploration/benchmark packs that do not rely on
 * floor sampling; leave {@code true} (default) for packs that need it.
 */
public final class SamplerFloorFeature {
    public static final boolean ENABLED = true;

    private SamplerFloorFeature() {}
}
