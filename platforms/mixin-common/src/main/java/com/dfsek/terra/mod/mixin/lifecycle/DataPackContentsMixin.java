package com.dfsek.terra.mod.mixin.lifecycle;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.commands.Commands;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.dfsek.terra.mod.util.MinecraftUtil;
import com.dfsek.terra.mod.util.TagUtil;


@Mixin(ReloadableServerResources.class)
public class DataPackContentsMixin {

    /*
     * #loadResources populates all tags in the registries
     */
    @Inject(method = "loadResources(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/LayeredRegistryAccess;Ljava/util/List;" +
                     "Lnet/minecraft/world/flag/FeatureFlagSet;" +
                     "Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/server/permissions/PermissionSet;Ljava/util/concurrent/Executor;" +
                     "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"))
    private static void injectReload(ResourceManager resourceManager,
                                     LayeredRegistryAccess<RegistryLayer> dynamicRegistries,
                                     List<Registry.PendingTags<?>> postponedTags, FeatureFlagSet enabledFeatures,
                                     Commands.CommandSelection commandSelection, PermissionSet permissions,
                                     Executor prepareExecutor,
                                     Executor applyExecutor, CallbackInfoReturnable<CompletableFuture<ReloadableServerResources>> cir) {
        RegistryAccess.Frozen dynamicRegistryManager = dynamicRegistries.compositeAccess();
        TagUtil.registerWorldPresetTags(dynamicRegistryManager.lookupOrThrow(Registries.WORLD_PRESET));

        Registry<Biome> biomeRegistry = dynamicRegistryManager.lookupOrThrow(Registries.BIOME);
        TagUtil.registerBiomeTags(biomeRegistry);
        MinecraftUtil.registerFlora(biomeRegistry);
    }
}
