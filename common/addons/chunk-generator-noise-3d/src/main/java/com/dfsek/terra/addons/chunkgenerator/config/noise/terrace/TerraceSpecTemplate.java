/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.config.noise.terrace;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import java.util.List;

import com.dfsek.terra.api.config.meta.Meta;


/** YAML: {@code terrain.terrace: {anchor, height, fade, passes: [{step, depth, offset, profile}]}}. */
public class TerraceSpecTemplate implements ObjectTemplate<TerraceSpec> {
    @Value("anchor")
    private @Meta Sampler anchor;

    @Value("height")
    private @Meta Sampler height;

    @Value("fade")
    @Default
    private @Meta Sampler fade = TerraceSpec.constant(1);

    @Value("passes")
    private @Meta List<TerraceSpec.@Meta Pass> passes;

    @Override
    public TerraceSpec get() {
        return new TerraceSpec(anchor, height, fade, passes.toArray(new TerraceSpec.Pass[0]));
    }
}
