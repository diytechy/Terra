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

import java.util.List;

import com.dfsek.terra.addons.biome.pipeline.PipelineBiomeProvider;
import com.dfsek.terra.addons.biome.pipeline.StructureSearchBiomeProvider;
import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;
import com.dfsek.terra.addons.biome.pipeline.api.biome.PipelineBiome;
import com.dfsek.terra.addons.biome.pipeline.pipeline.PipelineImpl;
import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


@SuppressWarnings({ "FieldMayBeFinal", "unused" })
public class BiomePipelineTemplate implements ObjectTemplate<BiomeProvider> {
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
    @Value("blend.interpolate")
    @Default
    @Description("""
                 Whether to interpolate between biome samples at sub-resolution.

                 When sampling at a resolution greater than 1, each resolution-sized
                 cell is normally assigned a single biome, producing axis-aligned
                 square steps at biome borders (especially on diagonals). With
                 interpolation enabled, each block stochastically selects one of its
                 four neighbouring cell samples weighted by proximity, dissolving the
                 steps into a noise-feathered gradient. Has no effect at resolution 1.""")
    protected @Meta boolean interpolate = false;
    @Value("blend.interpolation-sampler")
    @Default
    @Description("""
                 Optional sampler providing the per-block roll used for interpolation
                 (output mapped from [-1, 1] to [0, 1)). Defaults to a built-in
                 white-noise hash when unset.""")
    protected @Meta Sampler interpolationSampler = null;
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
    private @Meta Sampler structureSearchClassifier = null;

    @Value("structure-search.threshold")
    @Default
    @Description("Threshold for the structure search classifier. Default 0.0.")
    private double structureSearchThreshold = 0.0;

    @Value("structure-search.eligible-biome")
    @Default
    @Description("Biome returned for positions classified as eligible (sampler >= threshold). " +
                 "Must map to a vanilla biome in the structure's preferred_biomes tag.")
    private @Meta PipelineBiome structureSearchEligibleBiome = null;

    @Value("structure-search.ineligible-biome")
    @Default
    @Description("Biome returned for positions classified as ineligible (sampler < threshold). " +
                 "Must map to a vanilla biome NOT in the structure's preferred_biomes tag.")
    private @Meta PipelineBiome structureSearchIneligibleBiome = null;

    @Override
    public BiomeProvider get() {
        PipelineImpl pipeline = new PipelineImpl(source, stages, resolution, 128);
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
        return new PipelineBiomeProvider(pipeline, resolution, blendSampler, blendAmplitude, fastPath,
            interpolate, interpolationSampler);
    }
}
