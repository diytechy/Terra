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
        byte[] bytes;  // palette index (unsigned) while the palette has <= 256 entries
        short[] shorts; // palette index once it grows past 256

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
        int i = (((y - minY) & 15) << 8) | (z << 4) | x;
        if(s.shorts != null) {
            s.shorts[i] = (short) idx;
            return;
        }
        if(s.bytes == null) {
            if(idx == 0) return;
            s.bytes = new byte[4096];
        }
        if(idx > 255) {
            s.shorts = new short[4096];
            for(int j = 0; j < 4096; j++) s.shorts[j] = (short) (s.bytes[j] & 0xFF);
            s.bytes = null;
            s.shorts[i] = (short) idx;
            return;
        }
        s.bytes[i] = (byte) idx;
    }

    @Override
    public @NotNull BlockState getBlock(int x, int y, int z) {
        if(y < minY || y >= maxY) return air;
        Section s = sections[(y - minY) >> 4];
        if(s == null) return air;
        int i = (((y - minY) & 15) << 8) | (z << 4) | x;
        if(s.shorts != null) return s.palette.get(s.shorts[i]);
        if(s.bytes == null) return s.palette.get(0);
        return s.palette.get(s.bytes[i] & 0xFF);
    }

    /** Write every non-air block into {@code target} (same local coordinates). */
    void copyTo(ProtoChunk target) {
        for(int si = 0; si < sections.length; si++) {
            Section s = sections[si];
            if(s == null) continue;
            int y0 = minY + (si << 4);
            for(int i = 0; i < 4096; i++) {
                int idx = s.shorts != null ? s.shorts[i] : s.bytes != null ? s.bytes[i] & 0xFF : 0;
                BlockState b = s.palette.get(idx);
                if(b == air) continue;
                target.setBlock(i & 15, y0 + (i >> 8), (i >> 4) & 15, b);
            }
        }
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
