/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.feature.locator.config;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import com.dfsek.terra.addons.feature.locator.locators.SamplerMaxYLocator;
import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.structure.feature.Locator;


public class SamplerMaxYLocatorTemplate implements ObjectTemplate<Locator> {
    @Value("min-y")
    private @Meta int minY;

    @Value("max-y-sampler")
    private @Meta Sampler maxYSampler;

    @Override
    public Locator get() {
        return new SamplerMaxYLocator(minY, maxYSampler);
    }
}
