package com.dfsek.terra.mod.mixin.generalize;


import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.Level;
import net.minecraft.world.chunk.ChunkLoadProgress;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.CustomSpawner;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.concurrent.Executor;


@Mixin(ServerLevel.class)
public abstract class ServerWorldMixin extends Level {
    public ServerWorldMixin(MinecraftServer server, Executor workerExecutor, LevelStorageSource.Session session, ServerLevelData properties,
                            ResourceKey<Level> worldKey, LevelStem dimensionOptions,
                            ChunkLoadProgress chunkLoadProgress, boolean debugWorld, long seed,
                            List<CustomSpawner> spawners, boolean shouldTickTime, @Nullable RandomSequences randomSequencesState) {
        super(properties, worldKey, server.getRegistryManager(), dimensionOptions.dimensionTypeEntry(), false, debugWorld, seed,
            server.getMaxChainedNeighborUpdates());
    }

    @Redirect(method = "<init>",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/core/Holder;matchesKey(Lnet/minecraft/resources/ResourceKey;)Z"))
    public <T> boolean matchesKeyProxy(Holder<T> instance, ResourceKey<T> tRegistryKey) {
        if(tRegistryKey == BuiltinDimensionTypes.THE_END) {
            return (this.getRegistryKey() == Level.END);
        }
        return instance.matchesKey(tRegistryKey);
    }
}
