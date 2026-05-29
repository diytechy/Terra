package com.dfsek.terra.lifecycle.mixin.lifecycle;

import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dfsek.terra.lifecycle.LifecyclePlatform;
import com.dfsek.terra.lifecycle.mixin.RegistryLoadTaskAccessor;
import com.dfsek.terra.lifecycle.util.LifecycleUtil;
import com.dfsek.terra.lifecycle.util.RegistryHack;
import com.dfsek.terra.mod.CommonPlatform;
import com.dfsek.terra.mod.ModPlatform;


// 26.1 made RegistryDataLoader.load(...) async and moved the writable registries into per-registry
// RegistryLoadTask objects (private `registry` field, exposed via RegistryLoadTaskAccessor). The
// registries are frozen inside the `thenApplyAsync` lambda of the private load(...) overload, by
// `loadTasks.stream().filter(t -> t.freezeRegistry(...))`. We hook the HEAD of that lambda
// (lambda$load$2) to inject Terra content while every registry is fully populated yet still unfrozen.
@Mixin(RegistryDataLoader.class)
public class RegistryLoaderMixin {

    @Unique
    private static final AtomicBoolean LOADING_DYNAMIC_REGISTRIES = new AtomicBoolean(false);

    // The server-datapack worldgen load is the one we care about; only it carries the BIOME registry
    // through the ResourceManager overload. Set a flag here that the freeze hook consumes. The
    // network/client overload `load(Map, ResourceProvider, ...)` is intentionally not touched.
    @Inject(method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;" +
                     "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"))
    private static void terra_markDynamicRegistryLoad(ResourceManager resourceManager,
                                                      List<HolderLookup.RegistryLookup<?>> registries,
                                                      List<RegistryDataLoader.RegistryData<?>> entries,
                                                      CallbackInfoReturnable<java.util.concurrent.CompletableFuture<RegistryAccess.Frozen>> cir) {
        LOADING_DYNAMIC_REGISTRIES.set(entries.stream().anyMatch(entry -> entry.key() == Registries.BIOME));
    }

    // HEAD of the freeze lambda: at this point CompletableFuture.allOf(...) has completed, so every
    // task has finished registerElements/registerTags and nothing has been frozen yet.
    // NOTE: lambda$load$2 is a synthetic name tied to the body of RegistryDataLoader.load(...); if
    // that method changes between MC versions the index may shift -- re-derive via
    // `javap -p net.minecraft.resources.RegistryDataLoader` (the lambda returning RegistryAccess$Frozen).
    @Inject(
        method = "lambda$load$2(Ljava/util/List;Ljava/util/Map;Ljava/lang/Void;)Lnet/minecraft/core/RegistryAccess$Frozen;",
        at = @At("HEAD")
    )
    private static void terra_beforeFreeze(List<RegistryLoadTask<?>> loadTasks,
                                           Map<ResourceKey<?>, Exception> loadingErrors,
                                           Void ignored,
                                           CallbackInfoReturnable<RegistryAccess.Frozen> cir) {
        if(LOADING_DYNAMIC_REGISTRIES.getAndSet(false)) {
            ModPlatform platform = CommonPlatform.get();
            platform.getRawConfigRegistry().clear();
            WritableRegistry<Biome> biomes = extractRegistry(loadTasks, Registries.BIOME).orElseThrow();
            WritableRegistry<DimensionType> dimensionTypes = extractRegistry(loadTasks, Registries.DIMENSION_TYPE).orElseThrow();
            WritableRegistry<WorldPreset> worldPresets = extractRegistry(loadTasks, Registries.WORLD_PRESET).orElseThrow();
            WritableRegistry<NoiseGeneratorSettings> chunkGeneratorSettings = extractRegistry(loadTasks,
                Registries.NOISE_SETTINGS).orElseThrow();
            WritableRegistry<MultiNoiseBiomeSourceParameterList> multiNoiseBiomeSourceParameterLists = extractRegistry(loadTasks,
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).orElseThrow();
            WritableRegistry<Enchantment> enchantments = extractRegistry(loadTasks, Registries.ENCHANTMENT).orElseThrow();

            LifecyclePlatform.setRegistries(biomes, dimensionTypes, chunkGeneratorSettings, multiNoiseBiomeSourceParameterLists,
                enchantments);
            LifecycleUtil.initialize(biomes, worldPresets);
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T> Optional<WritableRegistry<T>> extractRegistry(List<RegistryLoadTask<?>> loadTasks,
                                                                     ResourceKey<? extends Registry<T>> key) {
        List<? extends WritableRegistry<?>> matches = loadTasks
            .stream()
            .map(task -> ((RegistryLoadTaskAccessor) task).terra_getRegistry())
            .filter(r -> r.key().equals(key))
            .toList();
        if(matches.size() > 1) {
            throw new IllegalStateException("Illegal number of registries returned: " + matches);
        } else if(matches.isEmpty()) {
            return Optional.empty();
        }
        WritableRegistry<T> registry = (WritableRegistry<T>) matches.getFirst();
        ((RegistryHack) registry).terra_bind();
        return Optional.of(registry);
    }
}
