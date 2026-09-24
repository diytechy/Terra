package com.dfsek.terra.cli.world;

import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.LongArrayTag;
import net.querz.nbt.tag.StringTag;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;

import com.dfsek.terra.cli.block.CLIBlockState;
import com.dfsek.terra.cli.world.chunk.CLIChunk;


/**
 * Minimal writer for the modern (Minecraft 1.18+) Anvil chunk format: full world height
 * (sections from {@code minHeight/16} to {@code maxHeight/16 - 1}), per-section block palettes with
 * non-spanning packed longs, and zlib-compressed chunks in a standard region file.
 * <p>
 * Biomes are written as a single-entry palette per section (plains); the harness keeps Terra's
 * own biome IDs in a separate per-column sidecar, because the region format stores only 4x4x4 cells.
 */
public final class AnvilWriter {
    /** Minecraft 1.21.1. Only used to label the data; readers in the harness ignore it. */
    public static final int DATA_VERSION = 3955;

    private AnvilWriter() {}

    public static void writeRegion(File file, Map<Integer, CLIChunk> chunks, int minHeight, int maxHeight) throws IOException {
        byte[][] payloads = new byte[1024][];
        for(Map.Entry<Integer, CLIChunk> e : chunks.entrySet()) {
            CompoundTag tag = chunkTag(e.getValue(), minHeight, maxHeight);
            byte[] raw = new NBTSerializer(false).toBytes(new NamedTag("", tag));
            payloads[e.getKey()] = zlib(raw);
        }
        try(RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(0);
            byte[] header = new byte[8192];
            int sector = 2;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int now = (int) (System.currentTimeMillis() / 1000);
            for(int i = 0; i < 1024; i++) {
                byte[] p = payloads[i];
                if(p == null) continue;
                int length = p.length + 1;
                int sectors = (length + 4 + 4095) / 4096;
                if(sectors > 255) throw new IOException("Chunk too large for region format: " + length + " bytes");
                int loc = (sector << 8) | sectors;
                header[i * 4] = (byte) (loc >>> 24);
                header[i * 4 + 1] = (byte) (loc >>> 16);
                header[i * 4 + 2] = (byte) (loc >>> 8);
                header[i * 4 + 3] = (byte) loc;
                header[4096 + i * 4] = (byte) (now >>> 24);
                header[4096 + i * 4 + 1] = (byte) (now >>> 16);
                header[4096 + i * 4 + 2] = (byte) (now >>> 8);
                header[4096 + i * 4 + 3] = (byte) now;
                byte[] chunkBytes = new byte[sectors * 4096];
                chunkBytes[0] = (byte) (length >>> 24);
                chunkBytes[1] = (byte) (length >>> 16);
                chunkBytes[2] = (byte) (length >>> 8);
                chunkBytes[3] = (byte) length;
                chunkBytes[4] = 2; // zlib
                System.arraycopy(p, 0, chunkBytes, 5, p.length);
                body.write(chunkBytes);
                sector += sectors;
            }
            out.write(header);
            out.write(body.toByteArray());
        }
    }

    private static byte[] zlib(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buf = new byte[65536];
        while(!deflater.finished()) {
            int n = deflater.deflate(buf);
            bos.write(buf, 0, n);
        }
        deflater.end();
        return bos.toByteArray();
    }

    static CompoundTag chunkTag(CLIChunk chunk, int minHeight, int maxHeight) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);
        root.putInt("xPos", chunk.getX());
        root.putInt("zPos", chunk.getZ());
        int minSection = Math.floorDiv(minHeight, 16);
        int maxSection = Math.floorDiv(maxHeight - 1, 16);
        root.putInt("yPos", minSection);
        root.putString("Status", "minecraft:full");
        ListTag<CompoundTag> sections = new ListTag<>(CompoundTag.class);
        for(int sy = minSection; sy <= maxSection; sy++) {
            sections.add(sectionTag(chunk, sy, minHeight, maxHeight));
        }
        root.put("sections", sections);
        return root;
    }

    private static CompoundTag sectionTag(CLIChunk chunk, int sy, int minHeight, int maxHeight) {
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
        int[] indices = new int[4096];
        CLIBlockState air = com.dfsek.terra.cli.handle.CLIWorldHandle.getAIR();
        for(int y = 0; y < 16; y++) {
            int wy = sy * 16 + y;
            for(int z = 0; z < 16; z++) {
                for(int x = 0; x < 16; x++) {
                    CLIBlockState state = (wy >= minHeight && wy < maxHeight) ? chunk.getBlock(x, wy, z) : air;
                    String name = state.getAsString(true);
                    Integer idx = paletteIndex.get(name);
                    if(idx == null) {
                        idx = paletteIndex.size();
                        paletteIndex.put(name, idx);
                        palette.add(state.getNbt());
                    }
                    indices[(y << 8) | (z << 4) | x] = idx;
                }
            }
        }
        CompoundTag blockStates = new CompoundTag();
        blockStates.put("palette", palette);
        if(palette.size() > 1) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            blockStates.put("data", new LongArrayTag(pack(indices, bits)));
        }
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sy);
        section.put("block_states", blockStates);
        CompoundTag biomes = new CompoundTag();
        ListTag<StringTag> biomePalette = new ListTag<>(StringTag.class);
        biomePalette.addString("minecraft:plains");
        biomes.put("palette", biomePalette);
        section.put("biomes", biomes);
        return section;
    }

    private static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for(int i = 0; i < values.length; i++) {
            int li = i / perLong;
            int shift = (i % perLong) * bits;
            out[li] |= ((long) values[i]) << shift;
        }
        return out;
    }
}
