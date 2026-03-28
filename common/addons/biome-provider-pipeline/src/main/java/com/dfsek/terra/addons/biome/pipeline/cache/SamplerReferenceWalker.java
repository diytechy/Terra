/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline.cache;

import com.dfsek.seismic.type.sampler.Sampler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;


/**
 * Counts references to pack-level samplers by scanning expression strings.
 *
 * Pack samplers are wrapped in `LastValueSampler(DeferredExpressionSampler{expression: "..."})`.
 * The DeferredExpressionSampler retains the raw expression string even after compilation.
 * By scanning these strings for pack sampler name calls, we determine which pack samplers
 * are referenced by other pack samplers (transitive usage).
 *
 * Stage samplers are compiled `ExpressionNoiseFunction` instances with no accessible expression
 * string. They are scanned when available (if somehow deferred), but the analysis relies primarily
 * on pack-to-pack reference counting.
 *
 * Used during {@link PipelineSamplerAnalysis} to determine usage frequencies for sampler selection.
 */
public final class SamplerReferenceWalker {

    private SamplerReferenceWalker() {
    }

    /**
     * Count references to pack-level samplers by scanning expression strings for sampler name calls.
     *
     * @param source the biome source (if any)
     * @param stages the pipeline stages
     * @param packSamplers map of (name -> sampler) for all pack-level samplers
     * @return map from pack sampler name to reference count
     */
    public static Map<String, Integer> countReferences(
        Source source,
        List<Stage> stages,
        Map<String, Sampler> packSamplers) {

        Map<String, Integer> counts = new HashMap<>();

        // Initialize all pack sampler counts to 0
        for (String name : packSamplers.keySet()) {
            counts.put(name, 0);
        }

        Set<String> packNames = packSamplers.keySet();

        System.out.println("[SamplerReferenceWalker] Starting reference count analysis for " + packNames.size() + " pack samplers");

        // Pass 1: Count pack-to-pack references (most accurate)
        // Each pack sampler's expression is scanned for references to other pack samplers
        int packExprCount = 0;
        for (Map.Entry<String, Sampler> entry : packSamplers.entrySet()) {
            String exprString = extractExpressionString(entry.getValue());
            if (exprString != null) {
                packExprCount++;
                scanForPackSamplerCalls(exprString, packNames, counts);
            }
        }
        System.out.println("[SamplerReferenceWalker] Scanned " + packExprCount + " pack sampler expressions for cross-references");

        // Pass 2: Count stage-to-pack references (only if expression strings are accessible)
        // In current CHIMERA setup, stage samplers are ExpressionNoiseFunction (compiled, no strings).
        // This pass handles cases where stages might be DeferredExpressionSampler instances.
        int stageExprCount = 0;
        for (Stage stage : stages) {
            if (stage != null) {
                Sampler stageSampler = stage.getSampler();
                if (stageSampler != null) {
                    String exprString = extractExpressionString(stageSampler);
                    if (exprString != null) {
                        stageExprCount++;
                        scanForPackSamplerCalls(exprString, packNames, counts);
                    }
                }
            }
        }
        if (stageExprCount > 0) {
            System.out.println("[SamplerReferenceWalker] Scanned " + stageExprCount + " stage expressions for pack sampler references");
        } else {
            System.out.println("[SamplerReferenceWalker] No stage expression strings available (stages are compiled ExpressionNoiseFunction)");
        }

        // Also scan source if present
        if (source != null) {
            Sampler sourceSampler = extractSampler(source);
            if (sourceSampler != null) {
                String exprString = extractExpressionString(sourceSampler);
                if (exprString != null) {
                    System.out.println("[SamplerReferenceWalker] Scanning source sampler expression");
                    scanForPackSamplerCalls(exprString, packNames, counts);
                }
            }
        }

        System.out.println("[SamplerReferenceWalker] Final reference counts: " + counts);
        return counts;
    }

    /**
     * Extract expression string from a sampler, unwrapping wrappers recursively.
     *
     * Handles:
     * - LastValueSampler: unwraps via public getDelegate() method
     * - DeferredExpressionSampler: extracts expression string via reflection
     * - ExpressionNoiseFunction: no expression string available (returns null)
     * - Other types: returns null
     */
    private static String extractExpressionString(Sampler sampler) {
        if (sampler == null) return null;

        // Unwrap LastValueSampler using its public getDelegate() method
        // Check by class simple name to avoid direct dependency on the noise addon class
        if (sampler.getClass().getSimpleName().equals("LastValueSampler")) {
            try {
                // Call the public getDelegate() method via reflection
                Sampler delegate = (Sampler) sampler.getClass().getMethod("getDelegate").invoke(sampler);
                return extractExpressionString(delegate);
            } catch (Exception ignored) {
                // If reflection fails, fall through to return null
            }
        }

        // Get expression string from DeferredExpressionSampler
        // Check by class simple name to avoid direct dependency on the noise addon class
        if (sampler.getClass().getSimpleName().equals("DeferredExpressionSampler")) {
            try {
                // Call the public getExpressionString() method
                Object result = sampler.getClass().getMethod("getExpressionString").invoke(sampler);
                if (result instanceof String) {
                    return (String) result;
                }
            } catch (Exception ignored) {
                // If reflection fails, fall through to return null
            }
        }

        // ExpressionNoiseFunction and other sampler types: expression string not available
        return null;
    }

    /**
     * Extract sampler from a Source via reflection on the getSampler() method.
     */
    private static Sampler extractSampler(Source source) {
        try {
            // Try to call getSampler() if available
            var method = source.getClass().getMethod("getSampler");
            Object result = method.invoke(source);
            if (result instanceof Sampler) {
                return (Sampler) result;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Scan an expression string for pack sampler name references.
     *
     * For each pack sampler name, checks if the expression contains `name + "("` or `name + " ("`.
     * Increments the reference count for each match found.
     *
     * The `name + "("` pattern avoids false positives for prefix-overlap names:
     * - `elevation(` in `elevation_with_mesas(` → no match (underscore is not paren)
     * - `continents(` in `continentsWithSpots(` → no match (W is not paren)
     * - `spot(` in `spotRadius(` → no match (R is not paren)
     */
    private static void scanForPackSamplerCalls(String expression, Set<String> packNames, Map<String, Integer> counts) {
        for (String name : packNames) {
            // Match both "name(" and "name (" (with or without space)
            if (expression.contains(name + "(") || expression.contains(name + " (")) {
                counts.merge(name, 1, Integer::sum);
            }
        }
    }
}
