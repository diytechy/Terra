/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.world.decoration;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.world.chunk.generation.ProtoChunk;


/**
 * A chunk's undecorated terrain, as the chunk generator produced it, stored compactly (one palette
 * per 16-block section, byte indices; sections never written stay implicit air).
 */
final class PristineChunk implements ProtoChunk {
    private final int minY, maxY;
    private final BlockState air;
    private final Section[] sections;

    PristineChunk(int minY, int maxY, BlockState air) {
        this.minY = minY;
        this.maxY = maxY;
        this.air = air;
        this.sections = new Section[(maxY - minY + 15) >> 4];
    }

    private static final class Section {
        final List<BlockState> palette = new ArrayList<>(4);
        short[] data; // index into palette; allocated on the second distinct state

        int index(BlockState s) {
            for(int i = 0; i < palette.size(); i++) if(palette.get(i) == s) return i;
            palette.add(s);
            return palette.size() - 1;
        }
    }

    @Override
    public void setBlock(int x, int y, int z, @NotNull BlockState state) {
        if(y < minY || y >= maxY) return;
        int si = (y - minY) >> 4;
        Section s = sections[si];
        if(s == null) {
            if(state.isAir() && state == air) return;
            s = sections[si] = new Section();
            s.palette.add(air);
        }
        int idx = s.index(state);
        if(s.data == null) {
            if(idx == 0) return;
            s.data = new short[4096];
        }
        s.data[(((y - minY) & 15) << 8) | (z << 4) | x] = (short) idx;
    }

    @Override
    public @NotNull BlockState getBlock(int x, int y, int z) {
        if(y < minY || y >= maxY) return air;
        Section s = sections[(y - minY) >> 4];
        if(s == null || s.data == null) return s == null ? air : s.palette.get(0);
        return s.palette.get(s.data[(((y - minY) & 15) << 8) | (z << 4) | x]);
    }

    @Override
    public int getMaxHeight() {
        return maxY;
    }

    @Override
    public Object getHandle() {
        return this;
    }
}
