/*
 * This file is part of Terra.
 *
 * Terra is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Terra is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Terra.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.dfsek.terra.mod.mixin.implementations.terra.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.collection.BoundedRegionArray;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.ticks.LevelTickAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Intrinsic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.block.state.BlockStateExtended;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.entity.Entity;
import com.dfsek.terra.api.entity.EntityType;
import com.dfsek.terra.api.world.ServerWorld;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;
import com.dfsek.terra.mod.generation.MinecraftChunkGeneratorWrapper;
import com.dfsek.terra.mod.implmentation.MinecraftEntityTypeExtended;
import com.dfsek.terra.mod.util.MinecraftUtil;


@Mixin(WorldGenRegion.class)
@Implements(@Interface(iface = ProtoWorld.class, prefix = "terraWorld$"))
public abstract class ChunkRegionMixin implements WorldGenLevel {
    private ConfigPack terra$config;


    @Shadow
    @Final
    private net.minecraft.server.level.ServerLevel world;

    @Shadow
    @Final
    private long seed;
    @Shadow
    @Final
    private ChunkAccess centerPos;

    @Shadow
    @Final
    private LevelTickAccess<Fluid> fluidTickScheduler;

    @Shadow
    @Final
    private LevelTickAccess<Block> blockTickScheduler;


    @Inject(at = @At("RETURN"),
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/collection/BoundedRegionArray;" +
                     "Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/world/level/chunk/ChunkAccess;)V")
    public void injectConstructor(net.minecraft.server.level.ServerLevel world, BoundedRegionArray chunks,
                                  ChunkStep generationStep, ChunkAccess centerPos, CallbackInfo ci) {
        this.terra$config = ((ServerLevel) world).getPack();
    }


    @Intrinsic(displace = true)
    public void terraWorld$setBlockState(int x, int y, int z, BlockState data, boolean physics) {
        BlockPos blockPos = new BlockPos(x, y, z);
        net.minecraft.world.level.block.state.BlockState state;

        int flags = physics ? 3 : 1042;
        boolean isExtended = MinecraftUtil.isCompatibleBlockStateExtended(data);

        if(isExtended) {
            BlockStateArgument arg = ((BlockStateArgument) data);
            state = arg.getBlockState();
            setBlockState(blockPos, state, flags);
            net.minecraft.world.level.chunk.ChunkAccess chunk = getChunk(blockPos);
            CompoundTag nbt = ((CompoundTag) (Object) ((BlockStateExtended) data).getData());
            MinecraftUtil.loadBlockEntity(chunk, world, blockPos, state, nbt);
        } else {
            state = (net.minecraft.world.level.block.state.BlockState) data;
            setBlockState(blockPos, state, flags);
        }

        if(physics) {
            MinecraftUtil.schedulePhysics(state, blockPos, getFluidTickScheduler(), getBlockTickScheduler());
        }
    }

    @Intrinsic
    public long terraWorld$getSeed() {
        return seed;
    }

    public int terraWorld$getMaxHeight() {
        return world.getTopYInclusive();
    }

    @Intrinsic(displace = true)
    public BlockState terraWorld$getBlockState(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return (BlockState) (this).getBlockState(pos);
    }

    public BlockEntity terraWorld$getBlockEntity(int x, int y, int z) {
        return MinecraftUtil.createBlockEntity(this, new BlockPos(x, y, z));
    }

    public int terraWorld$getMinHeight() {
        return world.getBottomY();
    }

    public ChunkGenerator terraWorld$getGenerator() {
        return ((MinecraftChunkGeneratorWrapper) world.getChunkManager().getChunkGenerator()).getHandle();
    }

    public BiomeProvider terraWorld$getBiomeProvider() {
        return terra$config.getBiomeProvider();
    }

    @SuppressWarnings("DataFlowIssue")
    public Entity terraWorld$spawnEntity(double x, double y, double z, EntityType data) {
        boolean isExtended = MinecraftUtil.isCompatibleEntityTypeExtended(data);
        net.minecraft.world.entity.Entity entity;
        if(isExtended) {
            MinecraftEntityTypeExtended type = ((MinecraftEntityTypeExtended) data);
            CompoundTag nbt = (CompoundTag) ((Object) type.getData());
            entity = net.minecraft.world.entity.EntityType.loadEntityWithPassengers(nbt, world, EntitySpawnReason.CHUNK_GENERATION, (entityx) -> {
                entityx.refreshPositionAndAngles(x, y, z, entityx.getYaw(), entityx.getPitch());
                return entityx;
            });
            spawnEntity(entity);
        } else {
            entity = ((net.minecraft.world.entity.EntityType<?>) data).create(world, EntitySpawnReason.CHUNK_GENERATION);
            entity.setPos(x, y, z);
            spawnEntity(entity);
        }

        return (Entity) entity;
    }

    public int terraWorld$centerChunkX() {
        return centerPos.getPos().x;
    }

    public int terraWorld$centerChunkZ() {
        return centerPos.getPos().z;
    }

    public ServerLevel terraWorld$getWorld() {
        return (ServerLevel) world;
    }

    public ConfigPack terraWorld$getPack() {
        return terra$config;
    }
}
