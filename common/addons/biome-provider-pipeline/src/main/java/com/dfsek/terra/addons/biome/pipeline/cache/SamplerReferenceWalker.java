/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline.cache;

import com.dfsek.seismic.type.sampler.Sampler;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;


/**
 * Walks a biome pipeline to count references to pack-level samplers.
 *
 * For each sampler found in the source and stages, this walker checks if the sampler
 * (or one it wraps) is an instance of a known pack-level sampler. Each match increments
 * the reference count for that sampler.
 *
 * Used during {@link PipelineSamplerAnalysis} to determine usage frequencies.
 */
public final class SamplerReferenceWalker {

    private SamplerReferenceWalker() {
    }

    /**
     * Walk the pipeline structure and count references to pack-level samplers.
     *
     * @param source the biome source
     * @param stages the pipeline stages
     * @param packSamplerInstances identity map of pack-level sampler instances; maps sampler -> name for lookup
     * @return a map from pack sampler name to reference count
     */
    public static Map<String, Integer> countReferences(
        Source source,
        List<Stage> stages,
        Map<Sampler, String> packSamplerInstances) {

        Map<String, Integer> counts = new HashMap<>();

        // Initialize counts for all known pack samplers
        for (String name : packSamplerInstances.values()) {
            counts.put(name, 0);
        }

        // Walk source
        if (source != null) {
            Sampler sourceSampler = extractSampler(source);
            if (sourceSampler != null) {
                countSampler(sourceSampler, packSamplerInstances, counts);
            }
        }

        // Walk stages
        for (Stage stage : stages) {
            Sampler stageSampler = extractSampler(stage);
            if (stageSampler != null) {
                countSampler(stageSampler, packSamplerInstances, counts);
            }
        }

        return counts;
    }

    /**
     * Extract a sampler from a source via reflection on the getSampler() method.
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
     * Extract a sampler from a stage via the default interface method or reflection.
     */
    private static Sampler extractSampler(Stage stage) {
        try {
            Sampler sampler = stage.getSampler();
            if (sampler != null) {
                return sampler;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Check if a sampler is a pack-level instance and increment its count.
     * Handles LastValueSampler wrapping transparently via reflection.
     */
    private static void countSampler(
        Sampler sampler,
        Map<Sampler, String> packSamplerInstances,
        Map<String, Integer> counts) {

        if (sampler == null) return;

        // Unwrap LastValueSampler to get the inner sampler (via reflection to avoid module dependency)
        Sampler toCheck = sampler;
        if (isLastValueSampler(sampler)) {
            Sampler delegate = getLastValueSamplerDelegate(sampler);
            if (delegate != null) {
                toCheck = delegate;
            }
        }

        // Check if this sampler is a known pack-level instance (by identity)
        String packSamplerName = packSamplerInstances.get(toCheck);
        if (packSamplerName != null) {
            counts.put(packSamplerName, counts.getOrDefault(packSamplerName, 0) + 1);
        }
    }

    /**
     * Check if a sampler is a LastValueSampler by class name.
     */
    private static boolean isLastValueSampler(Sampler sampler) {
        return sampler.getClass().getName().endsWith("LastValueSampler");
    }

    /**
     * Extract the delegate from a LastValueSampler via reflection.
     */
    private static Sampler getLastValueSamplerDelegate(Sampler sampler) {
        try {
            Field field = sampler.getClass().getDeclaredField("delegate");
            field.setAccessible(true);
            return (Sampler) field.get(sampler);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build a reverse map from sampler instance to pack-level sampler name.
     * This lets us quickly identify pack samplers by object identity during walks.
     */
    public static Map<Sampler, String> buildPackSamplerInstanceMap(Map<String, Sampler> packSamplers) {
        Map<Sampler, String> result = new IdentityHashMap<>();
        for (Map.Entry<String, Sampler> entry : packSamplers.entrySet()) {
            result.put(entry.getValue(), entry.getKey());
        }
        return result;
    }
}
