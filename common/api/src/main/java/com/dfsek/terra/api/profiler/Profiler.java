/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the common/api directory.
 */

package com.dfsek.terra.api.profiler;

import java.util.Map;


public interface Profiler {
    /**
     * Push a frame to this profiler.
     *
     * @param frame ID of frame.
     */
    void push(String frame);

    /**
     * Pop a frame from this profiler.
     *
     * @param frame ID of frame. Must match ID
     *              at the top of the profiler stack.
     */
    void pop(String frame);

    /**
     * Start profiling.
     */
    void start();

    /**
     * Stop profiling.
     */
    void stop();

    /**
     * Clear the profiler data.
     */
    void reset();

    /**
     * Record a timing directly to the flat key map, bypassing the push/pop state machine.
     * Safe to call from inside Caffeine cache-miss lambdas or other contexts where
     * push/pop would corrupt STACK_SIZE mid-session-startup.
     *
     * @param key   Full dot-separated path, e.g. "chunk_base_3d.sampler_cache.elevation_interpolator"
     * @param nanos Elapsed nanoseconds to record as one sample
     */
    default void record(String key, long nanos) {}

    /**
     * Get the profiler data.
     *
     * @return Profiler data.
     */
    Map<String, Timings> getTimings();
}
