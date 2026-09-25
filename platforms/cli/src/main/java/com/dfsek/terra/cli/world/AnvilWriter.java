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
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
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
    /**
     * Status for chunks that have terrain only. They lack light, heightmaps and real biomes (biomes are
     * a plains placeholder), so they must never be labelled {@code minecraft:full}.
     */
    public static final String STATUS_TERRAIN = "minecraft:carvers";
    /** Status for chunks that also ran the pack's feature stages. */
    public static final String STATUS_FEATURES = "minecraft:features";

    private AnvilWriter() {}

    /**
     * {@code status} if the CLI can honestly produce it ({@link #STATUS_TERRAIN} or {@link #STATUS_FEATURES}).
     * Anything later, {@code minecraft:full} above all, would claim light, heightmaps and biomes the CLI
     * doesn't write, and the server would serve the chunks as finished.
     *
     * @throws IllegalArgumentException for any other status
     */
    public static String requireSupportedStatus(String status) {
        if(STATUS_TERRAIN.equals(status) || STATUS_FEATURES.equals(status)) return status;
        throw new IllegalArgumentException("Unsupported chunk status '" + status + "': use " + STATUS_TERRAIN
                                           + " (terrain only) or " + STATUS_FEATURES
                                           + " (with features); the CLI never writes full chunks");
    }

    /**
     * @param dataVersion Minecraft data version of the target server (e.g. 4903 for Paper 26.2); required,
     *                    because a wrong value makes the server treat the chunks as old data to convert.
     * @param status      chunk status to record: {@link #STATUS_TERRAIN} or {@link #STATUS_FEATURES}
     * @throws IllegalArgumentException for any other status, before anything is written
     */
    public static void writeRegion(File file, Map<Integer, CLIChunk> chunks, int minHeight, int maxHeight, int dataVersion,
                                   String status) throws IOException {
        requireSupportedStatus(status);
        Map<Integer, byte[]> payloads = new java.util.HashMap<>();
        for(Map.Entry<Integer, CLIChunk> e : chunks.entrySet()) {
            CompoundTag tag = chunkTag(e.getValue(), minHeight, maxHeight, dataVersion, status);
            payloads.put(e.getKey(), zlib(new NBTSerializer(false).toBytes(new NamedTag("", tag))));
        }
        writeRegionPayloads(file.toPath(), payloads, (int) (System.currentTimeMillis() / 1000));
    }

    /**
     * Writes zlib-compressed chunk payloads (index = localX + 32 * localZ) into a region file.
     * <p>
     * If the file exists, every chunk that is not being written is preserved verbatim (its stored
     * record and timestamp are copied), so saving part of a region never deletes other chunks. The new
     * file is written next to the old one and moved into place atomically where the file system allows,
     * so a failure leaves the original intact. A malformed existing file is refused, not overwritten.
     */
    static void writeRegionPayloads(Path file, Map<Integer, byte[]> payloads, int timestamp) throws IOException {
        byte[][] records = new byte[1024][];
        int[] timestamps = new int[1024];
        if(Files.exists(file)) {
            byte[] old = Files.readAllBytes(file);
            if(old.length > 0) {
                if(old.length < 8192) throw new IOException("Refusing to modify " + file + ": shorter than a region header");
                ByteBuffer h = ByteBuffer.wrap(old);
                for(int i = 0; i < 1024; i++) {
                    int loc = h.getInt(i * 4);
                    int off = loc >>> 8, count = loc & 0xFF;
                    if(off == 0 && count == 0) continue;
                    long start = (long) off * 4096;
                    if(off < 2 || start + 5 > old.length) {
                        throw new IOException("Refusing to modify " + file + ": chunk " + i + " points outside the file");
                    }
                    int length = h.getInt((int) start);
                    if(length < 1 || start + 4 + length > old.length) {
                        throw new IOException("Refusing to modify " + file + ": chunk " + i + " has an invalid length");
                    }
                    records[i] = Arrays.copyOfRange(old, (int) start, (int) (start + 4 + length));
                    timestamps[i] = h.getInt(4096 + i * 4);
                }
            }
        }
        for(Map.Entry<Integer, byte[]> e : payloads.entrySet()) {
            byte[] p = e.getValue();
            byte[] rec = new byte[5 + p.length];
            ByteBuffer.wrap(rec).putInt(p.length + 1).put((byte) 2).put(p); // 2 = zlib
            records[e.getKey()] = rec;
            timestamps[e.getKey()] = timestamp;
        }

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try(OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            ByteBuffer header = ByteBuffer.allocate(8192);
            int sector = 2;
            for(int i = 0; i < 1024; i++) {
                if(records[i] == null) continue;
                int sectors = (records[i].length + 4095) / 4096;
                if(sectors > 255) throw new IOException("Chunk " + i + " too large for region format: " + records[i].length + " bytes");
                header.putInt(i * 4, (sector << 8) | sectors);
                header.putInt(4096 + i * 4, timestamps[i]);
                sector += sectors;
            }
            out.write(header.array());
            for(int i = 0; i < 1024; i++) {
                if(records[i] == null) continue;
                out.write(records[i]);
                int pad = (4096 - records[i].length % 4096) % 4096;
                out.write(new byte[pad]);
            }
        } catch(IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch(AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
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

    static CompoundTag chunkTag(CLIChunk chunk, int minHeight, int maxHeight, int dataVersion, String status) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", dataVersion);
        root.putInt("xPos", chunk.getX());
        root.putInt("zPos", chunk.getZ());
        int minSection = Math.floorDiv(minHeight, 16);
        int maxSection = Math.floorDiv(maxHeight - 1, 16);
        root.putInt("yPos", minSection);
        root.putString("Status", status);
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
