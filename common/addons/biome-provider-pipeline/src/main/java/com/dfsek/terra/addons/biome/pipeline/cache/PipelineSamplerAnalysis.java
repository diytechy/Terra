/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline.cache;

import com.dfsek.seismic.type.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;


/**
 * Analyzes a biome pipeline to select which pack samplers should be cached during chunk generation.
 *
 * Selection criteria:
 * 1. The sampler must be referenced in the pipeline (non-zero usage count)
 * 2. Weight is computed as complexity × usage_count
 * 3. Samplers are sorted by weight (highest first)
 * 4. Selection is capped by memory budget: max slots per thread = budget / (arraySize² × 8)
 *
 * Runs once at pipeline construction time (pack load phase).
 */
public final class PipelineSamplerAnalysis {
    private static final Logger logger = LoggerFactory.getLogger(PipelineSamplerAnalysis.class);

    // Memory budget per thread for sampler caches: 256 KB
    private static final long BUDGET_BYTES_PER_THREAD = 256 * 1024L;

    private PipelineSamplerAnalysis() {
    }

    /**
     * Analyze the pipeline and select samplers to cache.
     *
     * @param source the biome source
     * @param stages the pipeline stages
     * @param packSamplers map of (sampler name -> sampler) for all pack-level 2D samplers
     * @param arraySize the pipeline array size (side length)
     * @param complexityEstimator function that estimates sampler complexity (required; usually SamplerComplexityEstimator::estimate)
     * @return analysis result containing selected samplers and slot assignments
     */
    public static AnalysisResult analyze(
        Source source,
        List<Stage> stages,
        Map<String, Sampler> packSamplers,
        int arraySize,
        Function<Sampler, Integer> complexityEstimator) {

        if (packSamplers.isEmpty()) {
            return new AnalysisResult(new ArrayList<>(), 0);
        }

        // Step 1: Count references to each pack sampler
        Map<Sampler, String> samplerInstanceMap = SamplerReferenceWalker.buildPackSamplerInstanceMap(packSamplers);
        Map<String, Integer> referenceCounts = SamplerReferenceWalker.countReferences(source, stages, samplerInstanceMap);

        // Step 2: Estimate complexity for each sampler
        Map<String, Integer> complexities = new HashMap<>();
        for (Map.Entry<String, Sampler> entry : packSamplers.entrySet()) {
            int complexity = complexityEstimator.apply(entry.getValue());
            complexities.put(entry.getKey(), complexity);
        }

        // Step 3: Compute weights and filter to only used samplers
        List<SamplerWeight> weights = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : referenceCounts.entrySet()) {
            String name = entry.getKey();
            int useCount = entry.getValue();

            // Only include samplers that are actually used in the pipeline
            if (useCount > 0) {
                int complexity = complexities.getOrDefault(name, 0);
                long weight = (long) complexity * useCount;
                weights.add(new SamplerWeight(name, complexity, useCount, weight));
            }
        }

        // Step 4: Sort by weight descending
        weights.sort(Comparator.comparingLong((SamplerWeight w) -> w.weight).reversed());

        // Step 5: Determine max slots based on memory budget
        int cellCount = arraySize * arraySize;
        long bytesPerSampler = (long) cellCount * 8L;  // double[] = 8 bytes per element
        int maxSlots = (int) (BUDGET_BYTES_PER_THREAD / bytesPerSampler);
        if (maxSlots < 0) maxSlots = 0;

        // Step 6: Select top K samplers by weight
        List<SelectedSampler> selected = new ArrayList<>();
        int slot = 0;
        for (SamplerWeight w : weights) {
            if (slot >= maxSlots) break;
            Sampler sampler = packSamplers.get(w.name);
            selected.add(new SelectedSampler(w.name, sampler, slot));
            slot++;
        }

        // Log the selection
        logSelection(selected, weights, arraySize, maxSlots);

        return new AnalysisResult(selected, slot);
    }

    /**
     * Log the sampler selection summary for debugging and analysis.
     */
    private static void logSelection(
        List<SelectedSampler> selected,
        List<SamplerWeight> allWeights,
        int arraySize,
        int maxSlots) {

        if (selected.isEmpty()) {
            logger.info("Pipeline sampler caching: no samplers selected (none used in pipeline or budget exhausted)");
            return;
        }

        long bytesPerSampler = (long) arraySize * arraySize * 8L;
        long totalBytes = (long) selected.size() * bytesPerSampler;

        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline sampler caching: selected ").append(selected.size()).append(" samplers");
        if (maxSlots > 0) {
            sb.append(" (budget allows up to ").append(maxSlots).append(")");
        }
        sb.append("\n");
        sb.append("  Memory per sampler: ").append(bytesPerSampler / 1024).append(" KB\n");
        sb.append("  Total per thread: ").append(totalBytes / 1024).append(" KB\n");
        sb.append("  Selected samplers (by weight):\n");

        for (int i = 0; i < selected.size(); i++) {
            SelectedSampler sel = selected.get(i);
            // Find the corresponding weight entry for logging
            SamplerWeight w = allWeights.stream()
                .filter(wt -> wt.name.equals(sel.name))
                .findFirst()
                .orElse(null);

            if (w != null) {
                sb.append(String.format("    [%d] %s: complexity=%d, uses=%d, weight=%d\n",
                    i, sel.name, w.complexity, w.useCount, w.weight));
            } else {
                sb.append(String.format("    [%d] %s\n", i, sel.name));
            }
        }

        logger.info(sb.toString());
    }

    /**
     * Result of pipeline sampler analysis.
     */
    public static class AnalysisResult {
        public final List<SelectedSampler> selected;
        public final int numSlots;

        public AnalysisResult(List<SelectedSampler> selected, int numSlots) {
            this.selected = selected;
            this.numSlots = numSlots;
        }
    }

    /**
     * A selected sampler with its assigned cache slot.
     */
    public static class SelectedSampler {
        public final String name;
        public final Sampler sampler;
        public final int slot;

        public SelectedSampler(String name, Sampler sampler, int slot) {
            this.name = name;
            this.sampler = sampler;
            this.slot = slot;
        }
    }

    /**
     * Internal: sampler weight calculation.
     */
    private static class SamplerWeight {
        final String name;
        final int complexity;
        final int useCount;
        final long weight;

        SamplerWeight(String name, int complexity, int useCount, long weight) {
            this.name = name;
            this.complexity = complexity;
            this.useCount = useCount;
            this.weight = weight;
        }
    }
}
