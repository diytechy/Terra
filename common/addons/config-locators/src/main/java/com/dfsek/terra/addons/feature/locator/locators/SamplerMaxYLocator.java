/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.feature.locator.locators;

import com.dfsek.seismic.type.sampler.Sampler;

import com.dfsek.terra.api.structure.feature.BinaryColumn;
import com.dfsek.terra.api.structure.feature.Locator;
import com.dfsek.terra.api.world.chunk.generation.util.Column;


public class SamplerMaxYLocator implements Locator {
    private final int minY;
    private final Sampler maxYSampler;

    public SamplerMaxYLocator(int minY, Sampler maxYSampler) {
        this.minY = minY;
        this.maxYSampler = maxYSampler;
    }

    @Override
    public BinaryColumn getSuitableCoordinates(Column<?> column) {
        long seed = column.getWorld().getSeed();
        int x = column.getX();
        int z = column.getZ();
        double maxY = maxYSampler.getSample(seed, x, z);
        return column.newBinaryColumn(y -> y >= minY && y <= maxY);
    }
}
