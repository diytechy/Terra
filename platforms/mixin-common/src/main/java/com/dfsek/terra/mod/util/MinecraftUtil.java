package com.dfsek.terra.mod.util;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.math.intprovider.IntProviderType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.block.entity.Container;
import com.dfsek.terra.api.block.entity.MobSpawner;
import com.dfsek.terra.api.block.entity.Sign;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.entity.EntityType;
import com.dfsek.terra.api.util.range.ConstantRange;
import com.dfsek.terra.mod.CommonPlatform;
import com.dfsek.terra.mod.config.PreLoadCompatibilityOptions;
import com.dfsek.terra.mod.config.ProtoPlatformBiome;
import com.dfsek.terra.mod.data.Codecs;
import com.dfsek.terra.mod.implmentation.MinecraftEntityTypeExtended;
import com.dfsek.terra.mod.implmentation.TerraIntProvider;
import com.dfsek.terra.mod.mixin_ifaces.FloraFeatureHolder;


public final class MinecraftUtil {
    public static final Logger logger = LoggerFactory.getLogger(MinecraftUtil.class);

    private MinecraftUtil() {

    }

    public static <T> Optional<Holder<T>> getEntry(Registry<T> registry, Identifier identifier) {
        return registry.getOptionalValue(identifier)
            .flatMap(id -> Optional.ofNullable(registry.getEntry(id)));
    }

    public static BlockEntity createBlockEntity(LevelAccessor worldAccess, BlockPos pos) {
        net.minecraft.world.level.block.entity.BlockEntity entity = worldAccess.getBlockEntity(pos);
        if(entity instanceof SignBlockEntity) {
            return (Sign) entity;
        } else if(entity instanceof SpawnerBlockEntity) {
            return (MobSpawner) entity;
        } else if(entity instanceof RandomizableContainerBlockEntity) {
            return (Container) entity;
        }
        return null;
    }

    public static void schedulePhysics(BlockState blockState, BlockPos blockPos, TickAccess<Fluid> fluidScheduler,
                                       TickAccess<Block> blockScheduler) {
        if(blockState.isLiquid()) {
            fluidScheduler.scheduleTick(ScheduledTick.create(blockState.getFluidState().getFluid(), blockPos));
        } else {
            blockScheduler.scheduleTick(ScheduledTick.create(blockState.getBlock(), blockPos));
        }
    }

    public static boolean isCompatibleBlockStateExtended(com.dfsek.terra.api.block.state.BlockState blockState) {
        return blockState.isExtended() && BlockStateArgument.class.isAssignableFrom(blockState.getClass());
    }

    //[Vanilla Copy]
    public static void loadBlockEntity(ChunkAccess chunk, Level world, BlockPos blockPos, BlockState state, CompoundTag nbt) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity;
        if("DUMMY".equals(nbt.getString("id", ""))) {
            if(state.hasBlockEntity()) {
                blockEntity = ((EntityBlock) state.getBlock()).createBlockEntity(blockPos, state);
            } else {
                blockEntity = null;
            }
        } else {
            blockEntity = net.minecraft.world.level.block.entity.BlockEntity.createFromNbt(blockPos, state, nbt, world.getRegistryManager());
        }

        if(blockEntity != null) {
            blockEntity.setWorld(world);
            chunk.setBlockEntity(blockEntity);
        }
    }

    public static boolean isCompatibleEntityTypeExtended(EntityType entityType) {
        return entityType.isExtended() && MinecraftEntityTypeExtended.class.isAssignableFrom(entityType.getClass());
    }

    public static void registerIntProviderTypes() {
        IntProviderType<TerraIntProvider> CONSTANT = IntProviderType.register("terra:constant_range",
            Codecs.TERRA_CONSTANT_RANGE_INT_PROVIDER_TYPE);

        TerraIntProvider.TERRA_RANGE_TYPE_TO_INT_PROVIDER_TYPE.put(ConstantRange.class, CONSTANT);
    }

    public static void registerFlora(Registry<net.minecraft.world.level.biome.Biome> biomeRegistry) {
        logger.info("Injecting flora into Terra biomes...");
        CommonPlatform.get().getConfigRegistry().forEach(pack -> { // Register all Terra biomes.
            PreLoadCompatibilityOptions compatibilityOptions = pack.getContext().get(PreLoadCompatibilityOptions.class);
            if(compatibilityOptions.isInjectFlora()) {
                pack.getCheckedRegistry(com.dfsek.terra.api.world.biome.Biome.class)
                    .forEach((id, biome) -> {
                        registerFlora(biome, pack, id, biomeRegistry);
                    });
            }
        });
    }

    private static void registerFlora(com.dfsek.terra.api.world.biome.Biome biome, ConfigPack pack,
                                      com.dfsek.terra.api.registry.key.RegistryKey id,
                                      Registry<net.minecraft.world.level.biome.Biome> biomeRegistry) {
        ResourceKey<net.minecraft.world.level.biome.Biome> vanillaKey = ((ProtoPlatformBiome) biome.getPlatformBiome()).get(biomeRegistry);
        biomeRegistry.getOptionalValue(vanillaKey)
            .ifPresentOrElse(vanillaBiome -> {
                    Identifier terraBiomeIdentifier = Identifier.of("terra", BiomeUtil.createBiomeID(pack, id));
                    biomeRegistry.getOptionalValue(terraBiomeIdentifier).ifPresentOrElse(
                        terraBiome -> {
                            List<ConfiguredFeature<?, ?>> flowerFeatures = List.copyOf(
                                vanillaBiome.getGenerationSettings()
                                    .getFlowerFeatures());
                            logger.debug("Injecting flora into biome" +
                                         " {} : {}", terraBiomeIdentifier,
                                flowerFeatures);
                            ((FloraFeatureHolder) terraBiome.getGenerationSettings()).setFloraFeatures(
                                flowerFeatures);
                        },
                        () -> logger.error(
                            "No such biome: {}",
                            terraBiomeIdentifier)
                    );
                },
                () -> logger.error("No vanilla biome: {}", vanillaKey));
    }

    public static ResourceKey<Biome> registerBiomeKey(Identifier identifier) {
        return ResourceKey.of(Registries.BIOME, identifier);
    }

    public static ResourceKey<DimensionType> registerDimensionTypeKey(Identifier identifier) {
        return ResourceKey.of(Registries.DIMENSION_TYPE, identifier);
    }
}
