/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.noise;

import com.dfsek.seismic.type.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;


/**
 * Estimates computational complexity of samplers via recursive type inspection.
 *
 * Uses instanceof dispatch with a fallback heuristic when implementation details are inaccessible.
 * Results are memoized by sampler instance identity to handle DAG-shaped sampler graphs.
 */
public final class SamplerComplexityEstimator {
    private static final Logger logger = LoggerFactory.getLogger(SamplerComplexityEstimator.class);
    private static final Map<Sampler, Integer> memo = new IdentityHashMap<>();

    private SamplerComplexityEstimator() {
    }

    /**
     * Estimate the complexity of a sampler.
     * Higher scores indicate more CPU-intensive evaluation.
     * Scores are memoized, so repeated calls on the same sampler instance are O(1).
     */
    public static int estimate(Sampler sampler) {
        if (sampler == null) return 0;

        Integer cached = memo.get(sampler);
        if (cached != null) return cached;

        int complexity = estimateImpl(sampler);
        memo.put(sampler, complexity);
        return complexity;
    }

    private static int estimateImpl(Sampler sampler) {
        String className = sampler.getClass().getName();

        // Transparent wrappers that delegate to an inner sampler
        if (isLastValueSampler(sampler)) {
            Sampler delegate = getLastValueSamplerDelegate(sampler);
            return estimate(delegate);
        }

        // Trivial samplers
        if (className.contains("ConstantNoise") || className.contains("WhiteNoise") ||
            className.contains("PositiveWhiteNoise")) {
            return 1;
        }

        // Simple noise generators
        if (className.contains("OpenSimplex2") || className.contains("Perlin") ||
            className.contains("Simplex") || className.contains("Value")) {
            return 8;
        }

        // Gaussian
        if (className.contains("Gaussian")) {
            return 10;
        }

        // Gabor
        if (className.contains("Gabor")) {
            return 40;
        }

        // Cellular
        if (className.contains("Cellular")) {
            return 25;
        }

        // Pseudo-erosion
        if (className.contains("PseudoErosion")) {
            return 80;
        }

        // Fractal / BrownianMotion
        if (className.contains("BrownianMotion") || className.contains("PingPong") ||
            className.contains("RidgedFractal")) {
            int octaves = getOctaves(sampler, 6);  // Default to 6 if not accessible
            Sampler child = getChild(sampler);
            int childComplexity = estimate(child);
            return octaves * Math.max(1, childComplexity);
        }

        // Domain warp
        if (className.contains("DomainWarp")) {
            Sampler child = getChild(sampler);
            Sampler warpSampler = getWarpSampler(sampler);
            return 2 * estimate(child) + estimate(warpSampler);
        }

        // Kernel
        if (className.contains("Kernel")) {
            Sampler child = getChild(sampler);
            int kernelSize = getKernelSize(sampler, 3);
            return kernelSize * estimate(child);
        }

        // Arithmetic operations
        if (className.contains("Addition")) {
            int left = estimate(getChildByIndex(sampler, 0));
            int right = estimate(getChildByIndex(sampler, 1));
            return left + right + 1;
        }
        if (className.contains("Subtraction")) {
            int left = estimate(getChildByIndex(sampler, 0));
            int right = estimate(getChildByIndex(sampler, 1));
            return left + right + 1;
        }
        if (className.contains("Multiplication")) {
            int left = estimate(getChildByIndex(sampler, 0));
            int right = estimate(getChildByIndex(sampler, 1));
            return left + right + 1;
        }
        if (className.contains("Division")) {
            int left = estimate(getChildByIndex(sampler, 0));
            int right = estimate(getChildByIndex(sampler, 1));
            return left + right + 1;
        }
        if (className.contains("Max")) {
            int left = estimate(getChildByIndex(sampler, 0));
            int right = estimate(getChildByIndex(sampler, 1));
            return left + right + 1;
        }
        if (className.contains("Min")) {
            int left = estimate(getChildByIndex(sampler, 0));
            int right = estimate(getChildByIndex(sampler, 1));
            return left + right + 1;
        }

        // Deferred / compiled expressions
        if (className.contains("DeferredExpression") || className.contains("ExpressionNoise")) {
            return 50;  // Conservative estimate
        }

        // CacheSampler (already cached, treat as cheap)
        if (className.contains("Cache")) {
            return 1;
        }

        // Normalizers and other utility samplers
        if (className.contains("Linear") || className.contains("Clamp") ||
            className.contains("Probability") || className.contains("Scale") ||
            className.contains("Posterization") || className.contains("CubicSpline") ||
            className.contains("Normalizer")) {
            Sampler child = getChild(sampler);
            return estimate(child) + 1;
        }

        // Unknown / opaque sampler type
        logger.debug("Unknown sampler type, using conservative estimate: {}", className);
        return 15;
    }

    private static boolean isLastValueSampler(Sampler sampler) {
        return sampler.getClass().getName().contains("LastValueSampler");
    }

    private static Sampler getLastValueSamplerDelegate(Sampler sampler) {
        try {
            Field f = sampler.getClass().getDeclaredField("delegate");
            f.setAccessible(true);
            return (Sampler) f.get(sampler);
        } catch (Exception e) {
            return null;
        }
    }

    private static int getOctaves(Sampler sampler, int defaultValue) {
        try {
            Field f = sampler.getClass().getDeclaredField("octaves");
            f.setAccessible(true);
            return f.getInt(sampler);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int getKernelSize(Sampler sampler, int defaultValue) {
        try {
            Field f = sampler.getClass().getDeclaredField("size");
            f.setAccessible(true);
            return f.getInt(sampler);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static Sampler getChild(Sampler sampler) {
        // Try common field names for single-child samplers
        String[] fieldNames = {"sampler", "child", "function", "noise"};
        for (String fieldName : fieldNames) {
            try {
                Field f = sampler.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object obj = f.get(sampler);
                if (obj instanceof Sampler) return (Sampler) obj;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Sampler getChildByIndex(Sampler sampler, int index) {
        // Try common field names for binary operations
        String[] fieldNames = {"left", "right", "a", "b", "first", "second"};
        if (index == 0 && fieldNames.length > 0) {
            try {
                Field f = sampler.getClass().getDeclaredField(fieldNames[0]);
                f.setAccessible(true);
                Object obj = f.get(sampler);
                if (obj instanceof Sampler) return (Sampler) obj;
            } catch (Exception ignored) {
            }
        }
        if (index == 1 && fieldNames.length > 1) {
            try {
                Field f = sampler.getClass().getDeclaredField(fieldNames[1]);
                f.setAccessible(true);
                Object obj = f.get(sampler);
                if (obj instanceof Sampler) return (Sampler) obj;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Sampler getWarpSampler(Sampler sampler) {
        try {
            Field f = sampler.getClass().getDeclaredField("warp");
            f.setAccessible(true);
            return (Sampler) f.get(sampler);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clear the memoization cache. Used for testing or if sampler graph changes.
     */
    public static void clearMemo() {
        memo.clear();
    }
}
