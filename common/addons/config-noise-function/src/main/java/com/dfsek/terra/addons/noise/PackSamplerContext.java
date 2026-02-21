/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.noise;

import com.dfsek.terra.addons.noise.config.DimensionApplicableSampler;
import com.dfsek.terra.addons.noise.config.templates.FunctionTemplate;
import com.dfsek.terra.api.properties.Properties;

import java.util.Map;


/**
 * Exposes the pack-level sampler and function maps via the pack context,
 * allowing external code (e.g., NoiseTool's DummyPack) to add samplers
 * incrementally for sequential/dependency-ordered loading.
 */
public class PackSamplerContext implements Properties {
    private final Map<String, DimensionApplicableSampler> samplers;
    private final Map<String, FunctionTemplate> functions;

    public PackSamplerContext(Map<String, DimensionApplicableSampler> samplers,
                              Map<String, FunctionTemplate> functions) {
        this.samplers = samplers;
        this.functions = functions;
    }

    public Map<String, DimensionApplicableSampler> getSamplers() {
        return samplers;
    }

    public Map<String, FunctionTemplate> getFunctions() {
        return functions;
    }
}
