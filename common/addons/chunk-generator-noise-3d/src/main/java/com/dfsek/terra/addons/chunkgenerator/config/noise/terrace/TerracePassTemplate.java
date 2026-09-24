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

import com.dfsek.terra.api.config.meta.Meta;


/** One nested terrace pass: step height (blocks), depth (0..1), phase offset (steps), profile name. */
public class TerracePassTemplate implements ObjectTemplate<TerraceSpec.Pass> {
    @Value("step")
    private @Meta Sampler step;

    @Value("depth")
    @Default
    private @Meta Sampler depth = TerraceSpec.constant(1);

    @Value("offset")
    @Default
    private @Meta Sampler offset = TerraceSpec.constant(0);

    @Value("profile")
    @Default
    private @Meta String profile = "TERRACE";

    @Override
    public TerraceSpec.Pass get() {
        return new TerraceSpec.Pass(step, depth, offset, TerraceSpec.parseProfile(profile));
    }
}
