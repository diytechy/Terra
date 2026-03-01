package com.dfsek.terra.addons.chunkgenerator.config;

import com.dfsek.tectonic.api.config.template.ConfigTemplate;
import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;

import java.util.ArrayList;
import java.util.List;

import com.dfsek.terra.addons.chunkgenerator.generation.math.SlantCalculationMethod;
import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.properties.Properties;


public class NoiseChunkGeneratorPackConfigTemplate implements ConfigTemplate, Properties {
    @Value("blend.terrain.elevation")
    @Default
    private @Meta int elevationBlend = 4;

    @Value("blend.terrain.defaults.distance")
    @Default
    private @Meta int defaultBlendDistance = 3;

    @Value("blend.terrain.defaults.step")
    @Default
    private @Meta int defaultBlendStep = 4;

    @Value("blend.terrain.defaults.weight")
    @Default
    private @Meta double defaultBlendWeight = 1;

    @Value("blend.terrain.defaults.weight-2d")
    @Default
    private @Meta double defaultElevationWeight = 1;

    @Value("blend.terrain.no-blend-tags")
    @Default
    private @Meta List<@Meta String> noBlendTags = new ArrayList<>();

    // Y-range outside which blending is skipped and the center biome sample is used directly.
    // Integer.MIN_VALUE / Integer.MAX_VALUE means no limit (blending over the full world height).
    @Value("blend.terrain.y-range.min")
    @Default
    private @Meta int blendMinY = Integer.MIN_VALUE;

    @Value("blend.terrain.y-range.max")
    @Default
    private @Meta int blendMaxY = Integer.MAX_VALUE;

    @Value("carving.resolution.horizontal")
    @Default
    private @Meta int horizontalRes = 4;

    @Value("carving.resolution.vertical")
    @Default
    private @Meta int verticalRes = 2;

    @Value("slant.calculation-method")
    @Default
    private @Meta SlantCalculationMethod slantCalculationMethod = SlantCalculationMethod.Derivative;

    @Value("slant.disable-palettes")
    @Default
    private @Meta boolean disableSlantPalettes = false;

    public int getElevationBlend() {
        return elevationBlend;
    }

    public int getHorizontalRes() {
        return horizontalRes;
    }

    public int getVerticalRes() {
        return verticalRes;
    }

    public SlantCalculationMethod getSlantCalculationMethod() {
        return slantCalculationMethod;
    }

    public boolean isSlantPalettesEnabled() {
        return !disableSlantPalettes;
    }

    public int getDefaultBlendDistance() {
        return defaultBlendDistance;
    }

    public int getDefaultBlendStep() {
        return defaultBlendStep;
    }

    public double getDefaultBlendWeight() {
        return defaultBlendWeight;
    }

    public double getDefaultElevationWeight() {
        return defaultElevationWeight;
    }

    public List<String> getNoBlendTags() {
        return noBlendTags;
    }

    public int getBlendMinY() {
        return blendMinY;
    }

    public int getBlendMaxY() {
        return blendMaxY;
    }
}
