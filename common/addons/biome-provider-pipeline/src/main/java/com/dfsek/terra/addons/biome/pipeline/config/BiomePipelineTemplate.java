/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline.config;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Description;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.dfsek.terra.addons.biome.pipeline.PipelineBiomeProvider;
import com.dfsek.terra.addons.biome.pipeline.StructureSearchBiomeProvider;
import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;
import com.dfsek.terra.addons.biome.pipeline.api.biome.PipelineBiome;
import com.dfsek.terra.addons.biome.pipeline.pipeline.PipelineImpl;
import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.profiler.Profiler;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


@SuppressWarnings({ "FieldMayBeFinal", "unused" })
public class BiomePipelineTemplate implements ObjectTemplate<BiomeProvider> {
    private final Profiler profiler;
    private final Map<String, Sampler> packSamplers;
    private final Function<Sampler, Integer> complexityEstimator;
    private final boolean debugProfiler;

    /**
     * Constructor for BiomePipelineTemplate with optional caching support.
     *
     * @param profiler the profiler
     * @param packSamplers map of pack-level samplers (may be null or empty)
     * @param complexityEstimator function to estimate sampler complexity (may be null)
     * @param debugProfiler whether to enable debug logging for sampler caching analysis
     */
    public BiomePipelineTemplate(Profiler profiler, Map<String, Sampler> packSamplers, Function<Sampler, Integer> complexityEstimator, boolean debugProfiler) {
        this.profiler = profiler;
        this.packSamplers = packSamplers;
        this.complexityEstimator = complexityEstimator;
        this.debugProfiler = debugProfiler;
    }

    // Backward-compatible constructor without caching
    public BiomePipelineTemplate(Profiler profiler) {
        this(profiler, null, null, false);
    }
    @Value("resolution")
    @Default
    @Description("""
                 The resolution at which to sample biomes.
                 
                 Larger values are quadratically faster, but produce lower quality results.
                 For example, a value of 3 would sample every 3 blocks.""")
    protected @Meta int resolution = 1;
    @Value("blend.sampler")
    @Default
    @Description("A sampler to use for blending the edges of biomes via domain warping.")
    protected @Meta Sampler blendSampler = Sampler.zero();
    @Value("blend.amplitude")
    @Default
    @Description("The amplitude at which to perform blending.")
    protected @Meta double blendAmplitude = 0d;
    @Value("pipeline.source")
    @Description("The Biome Source to use for initial population of biomes.")
    private @Meta Source source;
    @Value("pipeline.stages")
    @Description("A list of pipeline stages to apply to the result of #source")
    private @Meta List<@Meta Stage> stages;

    @Value("structure-search.classifier")
    @Default
    @Description("Sampler whose sign determines structure placement eligibility. " +
                 "Values >= threshold return the eligible biome (e.g. land); " +
                 "values < threshold return the ineligible biome (e.g. ocean). " +
                 "Used as a fast path during stronghold ring position computation " +
                 "to avoid evaluating the full pipeline for every search query.")
    private @Meta @Nullable Sampler structureSearchClassifier = null;

    @Value("structure-search.threshold")
    @Default
    @Description("Threshold for the structure search classifier. Default 0.0.")
    private double structureSearchThreshold = 0.0;

    @Value("structure-search.eligible-biome")
    @Default
    @Description("Biome returned for positions classified as eligible (sampler >= threshold). " +
                 "Must map to a vanilla biome in the structure's preferred_biomes tag.")
    private @Meta @Nullable PipelineBiome structureSearchEligibleBiome = null;

    @Value("structure-search.ineligible-biome")
    @Default
    @Description("Biome returned for positions classified as ineligible (sampler < threshold). " +
                 "Must map to a vanilla biome NOT in the structure's preferred_biomes tag.")
    private @Meta @Nullable PipelineBiome structureSearchIneligibleBiome = null;

    @Override
    public BiomeProvider get() {
        PipelineImpl pipeline = new PipelineImpl(source, stages, resolution, 64, profiler, packSamplers, complexityEstimator, debugProfiler);
        StructureSearchBiomeProvider fastPath = null;
        if(structureSearchClassifier != null
                && structureSearchEligibleBiome != null
                && structureSearchIneligibleBiome != null) {
            fastPath = new StructureSearchBiomeProvider(
                structureSearchClassifier,
                structureSearchThreshold,
                structureSearchEligibleBiome.getBiome(),
                structureSearchIneligibleBiome.getBiome()
            );
        }
        return new PipelineBiomeProvider(pipeline, resolution, blendSampler, blendAmplitude, profiler, fastPath);
    }
}
