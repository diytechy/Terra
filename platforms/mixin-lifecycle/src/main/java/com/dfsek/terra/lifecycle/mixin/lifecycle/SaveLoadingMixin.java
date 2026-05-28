package com.dfsek.terra.lifecycle.mixin.lifecycle;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.SaveLoading;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.dfsek.terra.mod.util.MinecraftUtil;


@Mixin(SaveLoading.class)
public class SaveLoadingMixin {
    @ModifyArg(
        method = "load(Lnet/minecraft/server/SaveLoading$ServerConfig;Lnet/minecraft/server/SaveLoading$LoadContextSupplier;" +
                 "Lnet/minecraft/server/SaveLoading$SaveApplierFactory;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)" +
                 "Ljava/util/concurrent/CompletableFuture;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/ReloadableServerResources;reload(Lnet/minecraft/resource/ResourceManager;" +
                     "Lnet/minecraft/core/LayeredRegistryAccess;Ljava/util/List;Lnet/minecraft/resource/featuretoggle/FeatureFlagSet;" +
                     "Lnet/minecraft/server/command/Commands$RegistrationEnvironment;ILjava/util/concurrent/Executor;" +
                     "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
        index = 1
    )
    private static LayeredRegistryAccess<RegistryLayer> grabManager(
        LayeredRegistryAccess<RegistryLayer> dynamicRegistries) {
        MinecraftUtil.registerFlora(dynamicRegistries.getCombinedRegistryManager().getOrThrow(Registries.BIOME));
        return dynamicRegistries;
    }
}
