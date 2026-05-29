package com.dfsek.terra.lifecycle.mixin.lifecycle;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.Proxy;
import java.util.Optional;

import com.dfsek.terra.lifecycle.LifecyclePlatform;


@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "<init>(Ljava/lang/Thread;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;" +
                     "Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/WorldStem;Ljava/util/Optional;" +
                     "Ljava/net/Proxy;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/server/Services;" +
                     "Lnet/minecraft/server/level/progress/LevelLoadListener;Z)V",
            at = @At("RETURN"))
    private void injectConstructor(Thread serverThread, LevelStorageSource.LevelStorageAccess session, PackRepository dataPackManager,
                                   WorldStem worldStem, Optional<GameRules> gameRules, Proxy proxy, DataFixer dataFixer,
                                   Services services, LevelLoadListener levelLoadListener, boolean bl, CallbackInfo ci) {
        LifecyclePlatform.setServer((MinecraftServer) (Object) this);
    }
}
