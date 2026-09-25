package com.dfsek.terra.cli.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


/** Region writing must never delete chunks it was not asked to write. */
public class AnvilWriterTest {
    /** Stored payload (after the compression-type byte) of chunk {@code index}, or null. */
    static byte[] payload(Path file, int index) throws IOException {
        byte[] b = Files.readAllBytes(file);
        ByteBuffer h = ByteBuffer.wrap(b);
        int loc = h.getInt(index * 4);
        if(loc == 0) return null;
        int start = (loc >>> 8) * 4096;
        int length = h.getInt(start);
        return Arrays.copyOfRange(b, start + 5, start + 4 + length);
    }

    static int timestamp(Path file, int index) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4096 + index * 4);
    }

    static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for(int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed);
        return b;
    }

    @Test
    public void partialWritePreservesOtherChunks(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("r.0.0.mca");
        byte[] a = bytes(100, 1), b = bytes(9000, 2), c = bytes(50, 3), d = bytes(5000, 4);
        AnvilWriter.writeRegionPayloads(f, Map.of(0, a, 5, b), 111);
        AnvilWriter.writeRegionPayloads(f, Map.of(5, c, 7, d), 222);
        assertArrayEquals(a, payload(f, 0));
        assertEquals(111, timestamp(f, 0));
        assertArrayEquals(c, payload(f, 5));
        assertEquals(222, timestamp(f, 5));
        assertArrayEquals(d, payload(f, 7));
        assertNull(payload(f, 1));
        assertEquals(0, Files.size(f) % 4096);
        assertFalse(Files.exists(dir.resolve("r.0.0.mca.tmp")));
    }

    @Test
    public void malformedExistingFileIsRefusedAndKept(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("r.0.0.mca");
        byte[] junk = bytes(9000, 7);
        ByteBuffer.wrap(junk).putInt(0, (999 << 8) | 1); // chunk 0 points far past the end
        Files.write(f, junk);
        assertThrows(IOException.class, () -> AnvilWriter.writeRegionPayloads(f, Map.of(3, bytes(10, 1)), 1));
        assertArrayEquals(junk, Files.readAllBytes(f));
    }

    @Test
    public void onlyTheStatusesTheCliCanProduceAreAccepted(@TempDir Path dir) {
        assertEquals(AnvilWriter.STATUS_TERRAIN, AnvilWriter.requireSupportedStatus("minecraft:carvers"));
        assertEquals(AnvilWriter.STATUS_FEATURES, AnvilWriter.requireSupportedStatus("minecraft:features"));
        for(String bad : new String[]{ "minecraft:full", "full", "minecraft:light", "" }) {
            assertThrows(IllegalArgumentException.class, () -> AnvilWriter.requireSupportedStatus(bad), bad);
        }
        Path region = dir.resolve("r.0.0.mca");
        assertThrows(IllegalArgumentException.class,
            () -> AnvilWriter.writeRegion(region.toFile(), Map.of(), -64, 320, 4903, "minecraft:full"));
        assertFalse(Files.exists(region)); // refused before anything is written
    }
}
