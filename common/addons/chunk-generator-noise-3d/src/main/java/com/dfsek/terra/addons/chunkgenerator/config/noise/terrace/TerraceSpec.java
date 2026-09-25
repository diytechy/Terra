/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.config.noise.terrace;

import com.dfsek.seismic.type.sampler.Sampler;


/**
 * A biome's terrace description, for the shared terrace blend ("blend the parameters, not the
 * finished staircases").
 * <p>
 * Instead of each biome building its own staircase and the blend averaging the staircases, every
 * terraced biome in the blend stencil contributes its <em>parameters</em>; the chunk generator
 * averages them with the blend weights it already computes and runs one terrace function:
 * <pre>
 *   i      = blended height                       (un-terraced relief above the anchor, blocks)
 *   for each pass k (nested, innermost first):
 *     s, o, d = blended step (blocks), offset (steps), depth (0..1)
 *     x       = frac(i / s - o)                   (phase: shared by every contributor)
 *     P(x)    = blended profile value at x        (each contributor's profile, weighted)
 *     i       = i + d * s * P(x)
 *   surface = blended anchor + blended fade * i
 * </pre>
 * The step count is anchored at {@code anchor}, a smooth shared field (e.g. base + elevation), so
 * every biome's step index is measured from the same zero. A biome that has fewer passes than
 * another contributes depth 0 to the missing passes.
 * <p>
 * Where only one biome contributes, the surface is exactly that biome's own terraced surface.
 */
public record TerraceSpec(Sampler anchor, Sampler height, Sampler fade, Pass[] passes, Validation validation) {
    public TerraceSpec(Sampler anchor, Sampler height, Sampler fade, Pass[] passes) {
        this(anchor, height, fade, passes, new Validation());
    }

    /**
     * Results of {@link SharedTerrace#reproduces} for this spec, per (biome base sampler, world seed).
     * Held by the spec, so it belongs to the pack that loaded it and is released with it on reload.
     * At most {@link #MAX_ENTRIES} results are kept, least recently used first: a pack normally sees a
     * handful of (biome, seed) pairs, and an evicted result is simply recomputed (it is deterministic),
     * which is cheaper than growing for every seed evaluated during the pack's lifetime.
     */
    public static final class Validation {
        public static final int MAX_ENTRIES = 64;

        private record Key(Sampler base, long seed) {}

        // Access order; guarded by itself. Computation runs outside the lock (it samples terrain), so two
        // racing callers may both compute a key; they get the same answer.
        private final java.util.LinkedHashMap<Key, Boolean> results = new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Key, Boolean> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        boolean get(Sampler base, long seed, java.util.function.BooleanSupplier compute) {
            Key k = new Key(base, seed);
            synchronized(results) {
                Boolean r = results.get(k);
                if(r != null) return r;
            }
            boolean r = compute.getAsBoolean();
            synchronized(results) {
                results.put(k, r);
            }
            return r;
        }

        public int size() {
            synchronized(results) {
                return results.size();
            }
        }
    }

    public static final int PROFILE_TERRACE = 0;
    public static final int PROFILE_STRATA = 1;
    public static final int PROFILE_PARABOLIC = 2;
    public static final int PROFILE_PARALINEAR = 3;

    public record Pass(Sampler step, Sampler depth, Sampler offset, int profile) {}

    /** A sampler returning {@code value} everywhere (seismic has no constant factory). */
    public static Sampler constant(double value) {
        return new Sampler() {
            @Override
            public double getSample(long seed, double x, double y) {
                return value;
            }

            @Override
            public double getSample(long seed, double x, double y, double z) {
                return value;
            }
        };
    }

    /** Profile shapes from CHIMERA's math/functions/terrace.yml; x in [0, 1). All are <= 0. */
    public static double profile(int profile, double x) {
        return switch(profile) {
            case PROFILE_STRATA -> -(x > 0.95 ? -(x - 1) / 0.05 : x / 0.95);
            case PROFILE_PARABOLIC -> (x - 0.5) * (x - 0.5) - 0.25;
            case PROFILE_PARALINEAR -> (0.25 < x && x < 0.75) ? 2 * (x - 0.5) * (x - 0.5) - 0.375 : Math.abs(x - 0.5) - 0.5;
            default -> Math.abs(x - 0.5) - 0.5;
        };
    }

    public static int parseProfile(String s) {
        return switch(s.toUpperCase()) {
            case "STRATA" -> PROFILE_STRATA;
            case "PARABOLIC" -> PROFILE_PARABOLIC;
            case "PARALINEAR" -> PROFILE_PARALINEAR;
            case "TERRACE", "LINEAR" -> PROFILE_TERRACE;
            default -> throw new IllegalArgumentException("Unknown terrace profile " + s);
        };
    }
}
