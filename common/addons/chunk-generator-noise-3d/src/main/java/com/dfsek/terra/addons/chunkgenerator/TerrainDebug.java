/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Compile-time feature flag for coordinate-targeted terrain density tracing.
 * <p>
 * When {@link #ENABLED} is {@code false}, all {@code if (TerrainDebug.ENABLED)} guards are
 * dead-code-eliminated by javac / the JIT — zero runtime cost, no allocations, no log calls.
 * Flip to {@code true} and recompile to activate tracing at the configured target coordinate.
 * <p>
 * Traces are emitted at three pipeline stages:
 * <ul>
 *   <li>{@code [CI]}  — ChunkInterpolator sparse-grid construction (blended noise per sparse point)</li>
 *   <li>{@code [S3D]} — Sampler3D combined density (3D interp + 2D elevation + optional floor)</li>
 *   <li>{@code [NCG]} — NoiseChunkGenerator3D block placement decision</li>
 * </ul>
 * Grep server logs for these prefixes to extract only the relevant lines.
 */
public final class TerrainDebug {
    /** Set to {@code true} and recompile to enable tracing. Reset to {@code false} before shipping. */
    public static final boolean ENABLED = false;

    public static final Logger LOG = LoggerFactory.getLogger("TerrainDebug");

    // -------------------------------------------------------------------------
    // Target coordinate
    // -------------------------------------------------------------------------

    /** World seed to trace. Ignored when {@link #CHECK_SEED} is {@code false}. */
    public static final long TARGET_SEED = 7099699057166038826L;

    /** When {@code false}, traces fire for any seed at the target XZ position. */
    public static final boolean CHECK_SEED = true;

    public static final int TARGET_WORLD_X = 21;
    public static final int TARGET_WORLD_Z = -326;

    /** Inclusive Y range to trace (world-absolute). */
    public static final int TARGET_Y_MIN = 280;
    public static final int TARGET_Y_MAX = 320;

    // -------------------------------------------------------------------------
    // Derived chunk coords (pre-computed to avoid repeated floorDiv)
    // -------------------------------------------------------------------------

    /** Chunk index containing {@link #TARGET_WORLD_X}. */
    public static final int TARGET_CHUNK_X = Math.floorDiv(TARGET_WORLD_X, 16); //  1

    /** Chunk index containing {@link #TARGET_WORLD_Z}. */
    public static final int TARGET_CHUNK_Z = Math.floorDiv(TARGET_WORLD_Z, 16); // -21

    // -------------------------------------------------------------------------
    // Helpers — all calls eliminated by JIT when ENABLED = false
    // -------------------------------------------------------------------------

    public static boolean isTargetChunk(int chunkX, int chunkZ) {
        return chunkX == TARGET_CHUNK_X && chunkZ == TARGET_CHUNK_Z;
    }

    public static boolean isTargetSeed(long seed) {
        return !CHECK_SEED || seed == TARGET_SEED;
    }

    public static boolean isTargetY(int y) {
        return y >= TARGET_Y_MIN && y <= TARGET_Y_MAX;
    }

    private TerrainDebug() {}
}
