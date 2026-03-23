/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.biome.pipeline;

import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;
import com.dfsek.terra.addons.biome.pipeline.api.biome.PipelineBiome;
import com.dfsek.terra.addons.biome.pipeline.config.BiomePipelineTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.PipelineBiomeLoader;
import com.dfsek.terra.addons.biome.pipeline.config.source.SamplerSourceTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.stage.expander.ExpanderStageTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.stage.mutator.BorderListStageTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.stage.mutator.BorderStageTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.stage.mutator.ReplaceListStageTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.stage.mutator.ReplaceStageTemplate;
import com.dfsek.terra.addons.biome.pipeline.config.stage.mutator.SmoothStageTemplate;
import com.dfsek.terra.addons.manifest.api.AddonInitializer;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPostLoadEvent;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.inject.annotations.Inject;
import com.dfsek.terra.api.registry.CheckedRegistry;
import com.dfsek.terra.api.registry.Registry;
import com.dfsek.terra.api.util.reflection.TypeKey;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class BiomePipelineAddon implements AddonInitializer {

    public static final TypeKey<Supplier<ObjectTemplate<Source>>> SOURCE_REGISTRY_KEY = new TypeKey<>() {
    };

    public static final TypeKey<Supplier<ObjectTemplate<Stage>>> STAGE_REGISTRY_KEY = new TypeKey<>() {
    };
    public static final TypeKey<Supplier<ObjectTemplate<BiomeProvider>>> PROVIDER_REGISTRY_KEY = new TypeKey<>() {
    };
    @Inject
    private Platform platform;

    @Inject
    private BaseAddon addon;

    @Override
    public void initialize() {
        platform.getEventManager()
            .getHandler(FunctionalEventHandler.class)
            .register(addon, ConfigPackPreLoadEvent.class)
            .then(event -> {
                CheckedRegistry<Supplier<ObjectTemplate<BiomeProvider>>> providerRegistry = event.getPack().getOrCreateRegistry(
                    PROVIDER_REGISTRY_KEY);
                providerRegistry.register(addon.key("PIPELINE"), () -> {
                    // Extract pack samplers and complexity estimator from pack context
                    Map<String, Sampler> packSamplers = extractPackSamplers(event.getPack());
                    Function<Sampler, Integer> complexityEstimator = getComplexityEstimator();
                    return new BiomePipelineTemplate(platform.getProfiler(), packSamplers, complexityEstimator);
                });
            })
            .then(event -> {
                CheckedRegistry<Supplier<ObjectTemplate<Source>>> sourceRegistry = event.getPack().getOrCreateRegistry(
                    SOURCE_REGISTRY_KEY);
                sourceRegistry.register(addon.key("SAMPLER"), SamplerSourceTemplate::new);
            })
            .then(event -> {
                CheckedRegistry<Supplier<ObjectTemplate<Stage>>> stageRegistry = event.getPack().getOrCreateRegistry(
                    STAGE_REGISTRY_KEY);
                stageRegistry.register(addon.key("FRACTAL_EXPAND"), ExpanderStageTemplate::new);
                stageRegistry.register(addon.key("SMOOTH"), SmoothStageTemplate::new);
                stageRegistry.register(addon.key("REPLACE"), ReplaceStageTemplate::new);
                stageRegistry.register(addon.key("REPLACE_LIST"), ReplaceListStageTemplate::new);
                stageRegistry.register(addon.key("BORDER"), BorderStageTemplate::new);
                stageRegistry.register(addon.key("BORDER_LIST"), BorderListStageTemplate::new);
            })
            .failThrough();
        platform.getEventManager()
            .getHandler(FunctionalEventHandler.class)
            .register(addon, ConfigPackPostLoadEvent.class)
            .then(event -> {
                Registry<Biome> biomeRegistry = event.getPack().getRegistry(Biome.class);
                event.getPack().applyLoader(PipelineBiome.class, new PipelineBiomeLoader(biomeRegistry));
            });
    }

    /**
     * Extract pack-level samplers from the pack context.
     * Uses reflection to avoid direct dependency on NoiseAddon.
     */
    private static Map<String, Sampler> extractPackSamplers(com.dfsek.terra.api.config.ConfigPack pack) {
        try {
            // Try to get PackSamplerContext from pack context
            Object psc = pack.getContext().getByClassName("com.dfsek.terra.addons.noise.PackSamplerContext");

            if (psc == null) {
                return new HashMap<>();
            }

            // Get the packSamplers map via reflection
            var method = psc.getClass().getMethod("getPackSamplers");
            Object samplers = method.invoke(psc);

            if (samplers instanceof Map) {
                Map<String, Object> samplerMap = (Map<String, Object>) samplers;
                Map<String, Sampler> result = new HashMap<>();

                // Extract actual Sampler objects from DimensionApplicableSampler wrappers
                for (Map.Entry<String, Object> entry : samplerMap.entrySet()) {
                    Object das = entry.getValue();
                    if (das != null) {
                        // Try to get the sampler from DimensionApplicableSampler
                        try {
                            var getSamplerMethod = das.getClass().getMethod("getSampler");
                            Object sampler = getSamplerMethod.invoke(das);
                            if (sampler instanceof Sampler) {
                                result.put(entry.getKey(), (Sampler) sampler);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                return result;
            }
        } catch (Exception ignored) {
            // PackSamplerContext or NoiseAddon not available
        }

        return new HashMap<>();
    }

    /**
     * Get the complexity estimator function via reflection.
     * This avoids a direct dependency on SamplerComplexityEstimator.
     */
    private static Function<Sampler, Integer> getComplexityEstimator() {
        try {
            Class<?> estimatorClass = Class.forName("com.dfsek.terra.addons.noise.SamplerComplexityEstimator");
            var method = estimatorClass.getMethod("estimate", Sampler.class);

            return sampler -> {
                try {
                    Object result = method.invoke(null, sampler);
                    if (result instanceof Integer) {
                        return (Integer) result;
                    }
                } catch (Exception ignored) {
                }
                return 15;  // Conservative default
            };
        } catch (Exception ignored) {
            // SamplerComplexityEstimator not available
        }

        // Fallback: return a default estimator
        return sampler -> 15;
    }
}
