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

package com.dfsek.terra.mod.mixin.implementations.terra.chunk;

import com.dfsek.seismic.math.coord.CoordFunctions;
import net.minecraft.world.level.block.Block;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.block.state.BlockStateExtended;
import com.dfsek.terra.api.world.chunk.Chunk;
import com.dfsek.terra.mod.util.MinecraftUtil;


@Mixin(WorldGenRegion.class)
@Implements(@Interface(iface = ChunkAccess.class, prefix = "terraChunk$"))
public abstract class ChunkRegionMixin implements WorldGenLevel {

    @Shadow
    @Final
    private net.minecraft.world.level.chunk.ChunkAccess centerPos;

    @Shadow
    @Final
    private ServerLevel world;

    @Shadow
    @Final
    private LevelTickAccess<Block> blockTickScheduler;

    @Shadow
    @Final
    private LevelTickAccess<Fluid> fluidTickScheduler;

    @Shadow
    public abstract net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos);

    @Shadow
    @Nullable
    public abstract boolean setBlockState(BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags, int maxUpdateDepth);


    public void terraChunk$setBlock(int x, int y, int z, @NotNull BlockState data, boolean physics) {
        ChunkPos pos = centerPos.getPos();
        BlockPos blockPos = new BlockPos(CoordFunctions.chunkAndRelativeToAbsolute(pos.x, x), y,
            CoordFunctions.chunkAndRelativeToAbsolute(pos.z, z));
        net.minecraft.world.level.block.state.BlockState state;

        boolean isExtended = MinecraftUtil.isCompatibleBlockStateExtended(data);

        if(isExtended) {
            BlockStateArgument arg = ((BlockStateArgument) data);
            state = arg.getBlockState();
            setBlockState(blockPos, state, 0, 512);
            net.minecraft.world.level.chunk.ChunkAccess chunk = getChunk(blockPos);
            CompoundTag nbt = ((CompoundTag) (Object) ((BlockStateExtended) data).getData());
            MinecraftUtil.loadBlockEntity(chunk, world, blockPos, state, nbt);
        } else {
            state = (net.minecraft.world.level.block.state.BlockState) data;
            setBlockState(blockPos, state, 0, 512);
        }

        if(physics) {
            MinecraftUtil.schedulePhysics(state, blockPos, getFluidTickScheduler(), getBlockTickScheduler());
        }
    }

    public @NotNull BlockState terraChunk$getBlock(int x, int y, int z) {
        return (BlockState) ((WorldGenRegion) (Object) this).getBlockState(
            new BlockPos(x + (centerPos.getPos().x << 4), y, z + (centerPos.getPos().z << 4)));
    }

    public int terraChunk$getX() {
        return centerPos.getPos().x;
    }

    public int terraChunk$getZ() {
        return centerPos.getPos().z;
    }
}
