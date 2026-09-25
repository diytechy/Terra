package com.dfsek.terra.world.decoration;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.dfsek.terra.api.block.BlockType;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.block.state.properties.Property;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.ProtoChunk;
import com.dfsek.terra.api.world.chunk.generation.util.Palette;
import com.dfsek.terra.api.world.info.WorldProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Prefetch reservation, backpressure and lifecycle of the order-independent decorator's terrain cache. */
public class DecoratorCacheTest {
    record S(String id) implements BlockState {
        @Override public boolean matches(BlockState other) { return other instanceof S s && s.id.equals(id); }
        @Override public <T extends Comparable<T>> boolean has(Property<T> p) { return false; }
        @Override public <T extends Comparable<T>> T get(Property<T> p) { return null; }
        @Override public <T extends Comparable<T>> BlockState set(Property<T> p, T v) { return this; }
        @Override public BlockType getBlockType() { return null; }
        @Override public String getAsString(boolean properties) { return id; }
        @Override public boolean isAir() { return id.equals("air"); }
        @Override public Object getHandle() { return this; }
    }

    static final S AIR = new S("air"), STONE = new S("stone");

    /** Counts terrain generations per chunk; each takes {@code delayMs}. */
    static final class CountingGenerator implements ChunkGenerator {
        final Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        final long delayMs;

        CountingGenerator(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void generateChunkData(@NotNull ProtoChunk chunk, @NotNull WorldProperties world, @NotNull BiomeProvider b, int cx, int cz) {
            calls.computeIfAbsent(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for(int x = 0; x < 16; x++) for(int z = 0; z < 16; z++) for(int y = 0; y < 8; y++) chunk.setBlock(x, y, z, STONE);
        }

        @Override public BlockState getBlock(WorldProperties w, int x, int y, int z, BiomeProvider b) { return AIR; }
        @Override public Palette getPalette(int x, int y, int z, WorldProperties w, BiomeProvider b) { return null; }

        int maxCallsPerChunk() {
            return calls.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
        }
    }

    static final WorldProperties PROPS = new WorldProperties() {
        @Override public long getSeed() { return 1; }
        @Override public int getMaxHeight() { return 32; }
        @Override public int getMinHeight() { return 0; }
        @Override public Object getHandle() { return this; }
    };

    static OrderIndependentDecorator decorator(ChunkGenerator gen) {
        ConfigPack pack = (ConfigPack) Proxy.newProxyInstance(DecoratorCacheTest.class.getClassLoader(), new Class<?>[]{ ConfigPack.class },
            (p, m, a) -> null);
        return new OrderIndependentDecorator(pack, List.of(), gen, AIR, PROPS, null);
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("terra.decoration.prefetchQueue");
        System.clearProperty("terra.decoration.prefetchThreads");
        System.clearProperty("terra.decoration.pristineCacheMB");
    }

    @Test
    public void terrainCacheIsBoundedByBytesAndEvictedChunksAreRecomputedIdentically() {
        System.setProperty("terra.decoration.pristineCacheMB", "1");
        CountingGenerator gen = new CountingGenerator(0);
        OrderIndependentDecorator d = decorator(gen);
        PristineChunk first = d.pristine(0, 0);
        long one = first.estimatedBytes();
        long max = 0;
        for(int i = 1; i < 400; i++) {
            d.pristine(i, 0);
            max = Math.max(max, d.cacheBytes()[0]);
        }
        assertTrue(max <= (1 << 20) + one, "cache held " + max + " bytes");
        assertTrue(max > (1 << 20) - 2 * one, "cache far below its budget: " + max);
        PristineChunk again = d.pristine(0, 0); // evicted long ago
        assertEquals(2, gen.calls.get(0L).get());
        for(int y = 0; y < 32; y++) assertEquals(first.getBlock(3, y, 7), again.getBlock(3, y, 7));
        d.close();
        assertEquals(0, d.cacheBytes()[0]);
    }

    static void await(OrderIndependentDecorator d) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while(d.prefetchBacklog() > 0 && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    @Test
    public void concurrentPrefetchComputesEachChunkOnce() throws Exception {
        CountingGenerator gen = new CountingGenerator(2);
        OrderIndependentDecorator d = decorator(gen);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for(int t = 0; t < 8; t++) {
            callers.submit(() -> {
                go.await();
                for(int i = 0; i < 50; i++) d.prefetchAround(i % 3, i % 2);
                return null;
            });
        }
        go.countDown();
        callers.shutdown();
        assertTrue(callers.awaitTermination(30, TimeUnit.SECONDS));
        await(d);
        // Every chunk the prefetches touched, then asked for on demand: each generated exactly once.
        for(int x = -2; x <= 4; x++) for(int z = -2; z <= 3; z++) d.pristine(x, z);
        assertEquals(1, gen.maxCallsPerChunk());
        assertEquals(7 * 6, gen.calls.size());
        d.close();
    }

    @Test
    public void backlogIsBoundedAndDroppedWorkIsComputedOnDemand() throws Exception {
        System.setProperty("terra.decoration.prefetchThreads", "1");
        System.setProperty("terra.decoration.prefetchQueue", "4");
        CountingGenerator gen = new CountingGenerator(20);
        OrderIndependentDecorator d = decorator(gen);
        int maxBacklog = 0;
        for(int i = 0; i < 40; i++) {
            d.prefetchAround(i * 10, 0); // 25 new chunks per call
            maxBacklog = Math.max(maxBacklog, d.prefetchBacklog());
        }
        assertTrue(maxBacklog <= 1 + 4, "backlog " + maxBacklog);
        await(d);
        for(int i = 0; i < 40; i++) for(int dx = -2; dx <= 2; dx++) for(int dz = -2; dz <= 2; dz++) d.pristine(i * 10 + dx, dz);
        assertEquals(1, gen.maxCallsPerChunk()); // dropped reservations didn't block or duplicate
        d.close();
    }

    @Test
    public void closeCancelsQueuedWorkAndStopsPrefetching() throws Exception {
        System.setProperty("terra.decoration.prefetchThreads", "1");
        System.setProperty("terra.decoration.prefetchQueue", "32");
        CountingGenerator gen = new CountingGenerator(50);
        OrderIndependentDecorator d = decorator(gen);
        d.prefetchAround(0, 0);
        d.close();
        Thread.sleep(200);
        int afterClose = gen.calls.size();
        assertTrue(afterClose <= 2, "work continued after close: " + afterClose);
        d.prefetchAround(100, 100);
        Thread.sleep(100);
        assertEquals(afterClose, gen.calls.size());
        assertTrue(d.isClosed());
        assertFalse(d.fillFromCache(new PristineChunk(0, 32, AIR), 0, 0));
        d.pristine(5, 5); // still works after close, just uncached
        AtomicLong n = new AtomicLong(gen.calls.get((5L << 32) ^ 5L).get());
        assertEquals(1, n.get());
    }

    @Test
    public void aCallerNeverWaitsForPrefetchWorkThatIsOnlyQueued() throws Exception {
        // One slow prefetch thread and a long queue: most reservations sit in the queue. A caller asking
        // for such a chunk must compute it itself (claim the reservation), not wait behind the queue.
        System.setProperty("terra.decoration.prefetchThreads", "1");
        System.setProperty("terra.decoration.prefetchQueue", "256");
        CountingGenerator gen = new CountingGenerator(100);
        OrderIndependentDecorator d = decorator(gen);
        for(int i = 0; i < 8; i++) d.prefetchAround(i * 10, 0); // 200 chunks, ~20 s of queued work
        long t0 = System.nanoTime();
        d.pristine(72, 2); // reserved near the end of the queue
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2_000, "waited " + ms + " ms for queued work");
        d.close();
        Thread.sleep(300);
        assertEquals(1, gen.maxCallsPerChunk()); // the queued task did not compute it again
    }
}
