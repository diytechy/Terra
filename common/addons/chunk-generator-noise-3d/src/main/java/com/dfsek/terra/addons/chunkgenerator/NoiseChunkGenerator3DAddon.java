/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dfsek.terra.addons.chunkgenerator.config.NoiseChunkGeneratorPackConfigTemplate;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseConfigTemplate;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseSamplers;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.addons.chunkgenerator.config.palette.BiomePaletteTemplate;
import com.dfsek.terra.addons.chunkgenerator.config.palette.slant.SlantLayerTemplate;
import com.dfsek.terra.addons.chunkgenerator.generation.NoiseChunkGenerator3D;
import com.dfsek.terra.addons.chunkgenerator.generation.math.SlantCalculationMethod;
import com.dfsek.terra.addons.chunkgenerator.palette.BiomePaletteInfo;
import com.dfsek.terra.addons.chunkgenerator.palette.PaletteHolder;
import com.dfsek.terra.addons.chunkgenerator.palette.slant.SlantHolder;
import com.dfsek.terra.addons.manifest.api.AddonInitializer;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.event.events.config.ConfigurationLoadEvent;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPostLoadEvent;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.inject.annotations.Inject;
import com.dfsek.terra.api.properties.Context;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.chunk.generation.util.Palette;
import com.dfsek.terra.api.world.chunk.generation.util.provider.ChunkGeneratorProvider;


public class NoiseChunkGenerator3DAddon implements AddonInitializer {
    @Inject
    private Platform platform;

    @Inject
    private BaseAddon addon;

    @Override
    public void initialize() {
        PropertyKey<BiomePaletteInfo> paletteInfoPropertyKey = Context.create(BiomePaletteInfo.class);
        PropertyKey<BiomeNoiseProperties> noisePropertiesPropertyKey = Context.create(BiomeNoiseProperties.class);
        platform.getEventManager()
            .getHandler(FunctionalEventHandler.class)
            .register(addon, ConfigPackPreLoadEvent.class)
            .priority(1000)
            .then(event -> {

                event.getPack().applyLoader(SlantCalculationMethod.class,
                    (type, o, loader, depthTracker) -> SlantCalculationMethod.valueOf((String) o));

                NoiseChunkGeneratorPackConfigTemplate config = event.loadTemplate(new NoiseChunkGeneratorPackConfigTemplate());
                event.getPack().getContext().put(config);

                event.getPack()
                    .getOrCreateRegistry(ChunkGeneratorProvider.class)
                    .register(addon.key("NOISE_3D"),
                        pack -> new NoiseChunkGenerator3D(pack, platform, config.getElevationBlend(),
                            config.getHorizontalRes(),
                            config.getVerticalRes(), noisePropertiesPropertyKey,
                            paletteInfoPropertyKey, config.getSlantCalculationMethod(),
                            config.isSlantPalettesEnabled(),
                            config.getBlendMinY(), config.getBlendMaxY(),
                            config.getMinDensitySampler(), config.isMinDensitySmooth(),
                            config.getMinDensitySmoothK(), config.getMinDensitySkipTags()));
                event.getPack()
                    .applyLoader(SlantHolder.Layer.class, SlantLayerTemplate::new);
            })
            .failThrough();

        platform.getEventManager()
            .getHandler(FunctionalEventHandler.class)
            .register(addon, ConfigurationLoadEvent.class)
            .then(event -> {
                if(event.is(Biome.class)) {
                    NoiseChunkGeneratorPackConfigTemplate config = event.getPack().getContext().get(
                        NoiseChunkGeneratorPackConfigTemplate.class);

                    Biome biome = event.getLoadedObject(Biome.class);

                    biome.getContext().put(paletteInfoPropertyKey,
                        event.load(new BiomePaletteTemplate(platform,
                                config.getSlantCalculationMethod()))
                            .get());

                    BiomeNoiseProperties props = event.load(new BiomeNoiseConfigTemplate(
                        config.getDefaultBlendDistance(),
                        config.getDefaultBlendStep(),
                        config.getDefaultBlendWeight(),
                        config.getDefaultElevationWeight()
                    )).get();

                    List<String> noBlendTags = config.getNoBlendTags();
                    if(!noBlendTags.isEmpty() && biome.getTags().stream().anyMatch(noBlendTags::contains)) {
                        BiomeNoiseSamplers s = props.samplers();
                        props = new BiomeNoiseProperties(
                            new BiomeNoiseSamplers(s.base(), s.elevation(), s.carving(),
                                0, s.blendStep(), s.blendWeight(), s.elevationWeight()),
                            props.noiseHolder()
                        );
                    }

                    biome.getContext().put(noisePropertiesPropertyKey, props);
                }
            })
            .failThrough();

        platform.getEventManager()
            .getHandler(FunctionalEventHandler.class)
            .register(addon, ConfigPackPostLoadEvent.class)
            .then(event -> {
                Collection<Biome> biomes = event.getPack().getRegistry(Biome.class).entries();
                // Intern PaletteHolder instances: biomes with identical palette stacks (same Palette
                // objects in same order, same offset) share a single canonical PaletteHolder array,
                // eliminating duplicate Palette[] allocations that arise when many biomes inherit the
                // same palette configuration from a common abstract parent.
                Map<Map.Entry<List<Palette>, Integer>, PaletteHolder> holderIntern = new HashMap<>();
                biomes.forEach(biome -> {
                    BiomePaletteInfo info = biome.getContext().get(paletteInfoPropertyKey);
                    PaletteHolder holder = info.paletteHolder();
                    Map.Entry<List<Palette>, Integer> key = new AbstractMap.SimpleImmutableEntry<>(
                        Arrays.asList(holder.getPalettes()), holder.getOffset());
                    PaletteHolder canonical = holderIntern.computeIfAbsent(key, k -> holder);
                    if(canonical != holder) {
                        biome.getContext().put(paletteInfoPropertyKey,
                            new BiomePaletteInfo(canonical, info.slantHolder(), info.ocean(),
                                info.seaLevel(), info.seaLevelSampler(), info.updatePaletteWhenCarving()));
                    }
                });
            })
            .failThrough();
    }
}
