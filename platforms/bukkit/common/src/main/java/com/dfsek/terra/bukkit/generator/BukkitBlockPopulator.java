package com.dfsek.terra.bukkit.generator;

import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.bukkit.world.BukkitProtoWorld;


public class BukkitBlockPopulator extends BlockPopulator {
    private final BlockState air;
    private ConfigPack pack;

    private final java.util.function.Function<WorldInfo, com.dfsek.terra.world.decoration.OrderIndependentDecorator> decoratorFor;

    public BukkitBlockPopulator(ConfigPack pack, BlockState air,
                                java.util.function.Function<WorldInfo, com.dfsek.terra.world.decoration.OrderIndependentDecorator> decoratorFor) {
        this.pack = pack;
        this.air = air;
        this.decoratorFor = decoratorFor;
    }

    public void setPack(ConfigPack pack) {
        this.pack = pack;
    }

    // Diagnostics: -Dterra.stages.skip=a,b (or "all") / -Dterra.stages.only=a,b select which pack
    // generation stages run on this platform. Default: all stages.
    private static final java.util.Set<String> SKIP = parse(System.getProperty("terra.stages.skip"));
    private static final java.util.Set<String> ONLY = parse(System.getProperty("terra.stages.only"));

    private static java.util.Set<String> parse(String s) {
        if(s == null || s.isBlank()) return null;
        return java.util.Set.of(s.split(","));
    }

    static boolean runs(Object stage) {
        String id = stage instanceof com.dfsek.terra.api.registry.key.StringIdentifiable si ? si.getID() : stage.toString();
        if(SKIP != null && (SKIP.contains("all") || SKIP.contains(id))) return false;
        return ONLY == null || ONLY.contains(id);
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion limitedRegion) {
        BukkitProtoWorld world = new BukkitProtoWorld(limitedRegion, air, pack.getBiomeProvider());
        if(com.dfsek.terra.world.decoration.OrderIndependentDecorator.enabled()) {
            decoratorFor.apply(worldInfo).decorate(world);
            return;
        }
        pack.getStages().forEach(generationStage -> {
            if(runs(generationStage)) generationStage.populate(world);
        });
    }
}
