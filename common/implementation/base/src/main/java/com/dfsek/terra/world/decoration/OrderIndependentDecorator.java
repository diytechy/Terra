/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.world.decoration;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.entity.Entity;
import com.dfsek.terra.api.entity.EntityType;
import com.dfsek.terra.api.world.ServerWorld;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.DeferringWorld;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;
import com.dfsek.terra.api.world.chunk.generation.ProtoChunk;
import com.dfsek.terra.api.world.chunk.generation.stage.GenerationStage;
import com.dfsek.terra.api.world.info.WorldProperties;


/**
 * Feature decoration whose result does not depend on the order in which chunks are decorated.
 * <p>
 * <b>Problem.</b> A server decorates each chunk once, through a region that also covers its eight
 * neighbours. Features read that region (surface and pattern locators, structure checks) and write
 * into neighbours (trees, blobs, webs). Whether a neighbour was decorated before or after decides
 * what a feature reads and which of two overlapping writes survives, so chunk contents depended on
 * the server's (multi-threaded, load-driven) generation order.
 * <p>
 * <b>Scheme.</b>
 * <ol>
 *   <li>For every chunk X, all pack stages run once against a private view: X's undecorated terrain
 *       (regenerated from the chunk generator, cached) plus X's own writes so far, limited to X and
 *       its neighbours like the server region. The result, the <em>write set</em> W(X), depends only on
 *       the seed.</li>
 *   <li>Decorating chunk C writes <em>only C</em>: the writes of W(X) for X in C's 3x3 neighbourhood
 *       that land in C, merged in a fixed priority (neighbours in fixed order, C's own writes last,
 *       so a chunk's own features win inside it).</li>
 *   <li>A write is applied only where the live block still equals the undecorated terrain, so blocks
 *       placed by the server itself (vanilla structures, structure terrain adaptation) are kept.</li>
 *   <li>Block-entity and entity operations (loot, block state, entity spawns) are deferred
 *       ({@link DeferringWorld}) and run on the live world by the chunk that contains them.</li>
 * </ol>
 * <b>Trade-off.</b> Features no longer see features of other chunks (e.g. snow or vines are not
 * added to leaves that hang in from a neighbour); overlaps between two chunks' features resolve by
 * the fixed priority instead of by generation order.
 * <p>
 * Enable with {@code -Dterra.decoration.orderIndependent=true}.
 * <p>
 * <b>Memory.</b> Per decorator (one per world), the caches are bounded by estimated bytes, least
 * recently used first: undecorated terrain {@code terra.decoration.pristineCacheMB} (default 96) and
 * write sets {@code terra.decoration.writeSetCacheMB} (default 32), plus at most
 * {@code prefetchThreads + prefetchQueue} terrain chunks being prefetched. So about 130 MB per world
 * with the defaults. Evicted entries are recomputed identically when needed (slower, same result).
 * Platforms must {@link #close()} a world's decorator when the world unloads.
 */
public final class OrderIndependentDecorator {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderIndependentDecorator.class);
    private static final int[][] NEIGHBOURS_OWNER_LAST;

    static {
        List<int[]> l = new ArrayList<>();
        for(int dx = -1; dx <= 1; dx++) for(int dz = -1; dz <= 1; dz++) if(dx != 0 || dz != 0) l.add(new int[]{ dx, dz });
        l.add(new int[]{ 0, 0 });
        NEIGHBOURS_OWNER_LAST = l.toArray(new int[0][]);
    }

    public static boolean enabled() {
        return Boolean.getBoolean("terra.decoration.orderIndependent");
    }

    private final ConfigPack pack;
    private final List<GenerationStage> stages;
    private final ChunkGenerator generator;
    private final BiomeProvider biomes;
    private final BlockState air;
    private final long seed;
    private final int minY, maxY;
    private final ServerWorld serverWorld;
    private final WorldProperties properties;
    private final LruFutures<PristineChunk> pristine;
    private final LruFutures<WriteSet> writeSets;

    public OrderIndependentDecorator(ConfigPack pack, List<GenerationStage> stages, ChunkGenerator generator, BlockState air,
                                     ProtoWorld sample) {
        this(pack, stages, generator, air, sample, sample.getWorld());
    }

    /**
     * @param properties  seed and height range of the world
     * @param serverWorld returned by {@code getWorld()} of the private views (may be null)
     */
    public OrderIndependentDecorator(ConfigPack pack, List<GenerationStage> stages, ChunkGenerator generator, BlockState air,
                                     WorldProperties properties, ServerWorld serverWorld) {
        this.pack = pack;
        this.stages = List.copyOf(stages);
        this.generator = generator;
        this.biomes = pack.getBiomeProvider();
        this.air = air;
        this.seed = properties.getSeed();
        this.minY = properties.getMinHeight();
        this.maxY = properties.getMaxHeight();
        this.properties = properties;
        this.serverWorld = serverWorld;
        this.pristine = new LruFutures<>(Long.getLong("terra.decoration.pristineCacheMB", 96) << 20, PristineChunk::estimatedBytes);
        this.writeSets = new LruFutures<>(Long.getLong("terra.decoration.writeSetCacheMB", 32) << 20, WriteSet::estimatedBytes);
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static long pos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) (y + 2048) & 0xFFFL);
    }

    private static int posX(long p) { return (int) (p >> 38) << 6 >> 6; }

    private static int posZ(long p) { return (int) ((p >> 12) & 0x3FFFFFFL) << 6 >> 6; }

    private static int posY(long p) { return (int) (p & 0xFFFL) - 2048; }

    PristineChunk pristine(int cx, int cz) {
        return pristine.get(key(cx, cz), k -> computePristine(cx, cz));
    }

    private PristineChunk computePristine(int cx, int cz) {
        PristineChunk c = new PristineChunk(minY, maxY, air);
        generator.generateChunkData(c, properties, biomes, cx, cz);
        return c;
    }

    /**
     * Wrap the chunk the platform is about to generate terrain into, so the undecorated terrain is
     * captured as it is written (no regeneration later). Call {@link Capture#commit()} after the
     * generator has finished with the chunk.
     */
    public Capture capture(ProtoChunk target, int cx, int cz) {
        return new Capture(target, cx, cz);
    }

    /**
     * Background terrain prefetch. Bounded: a fixed number of threads
     * ({@code terra.decoration.prefetchThreads}, default cores/2) and a bounded queue
     * ({@code terra.decoration.prefetchQueue}, default 64). Prefetching is optional work, so when the
     * queue is full the request is dropped (its reservation cancelled) and the terrain is computed on
     * demand instead. Stopped by {@link #close()}.
     */
    private final java.util.concurrent.ThreadPoolExecutor prefetch = newPrefetchExecutor();
    private volatile boolean closed;

    private static java.util.concurrent.ThreadPoolExecutor newPrefetchExecutor() {
        int threads = Math.max(1, Integer.getInteger("terra.decoration.prefetchThreads",
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
        int queue = Math.max(1, Integer.getInteger("terra.decoration.prefetchQueue", 64));
        java.util.concurrent.ThreadPoolExecutor ex = new java.util.concurrent.ThreadPoolExecutor(threads, threads, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, "Terra-decoration-prefetch");
            t.setDaemon(true);
            return t;
        }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /**
     * Start computing, in the background, the undecorated terrain of chunks within 2 of (cx, cz) that
     * isn't cached yet: decorating a chunk needs terrain two chunks out, which the server usually hasn't
     * generated at that point. The server's own terrain step then reuses it ({@link #fillFromCache}).
     * Each chunk is reserved in the cache atomically before its task is queued, so concurrent callers
     * never queue the same chunk twice.
     */
    public void prefetchAround(int cx, int cz) {
        if(closed) return;
        for(int dx = -2; dx <= 2; dx++) {
            for(int dz = -2; dz <= 2; dz++) {
                int x = cx + dx, z = cz + dz;
                long k = key(x, z);
                CompletableFuture<PristineChunk> reserved = pristine.reserve(k);
                if(reserved == null) continue; // cached, being computed, or already queued
                try {
                    prefetch.execute(() -> pristine.completeReserved(k, reserved, kk -> computePristine(x, z)));
                } catch(java.util.concurrent.RejectedExecutionException full) {
                    pristine.cancel(k, reserved); // dropped: computed on demand later
                }
            }
        }
    }

    /** Number of prefetch tasks queued or running (diagnostics and tests). */
    public int prefetchBacklog() {
        return prefetch.getQueue().size() + prefetch.getActiveCount();
    }

    /**
     * Retire this decorator (world unloaded or pack reloaded): stop prefetching, cancel queued work and
     * drop the caches. Later calls still work but compute without caching.
     */
    public void close() {
        closed = true;
        prefetch.shutdownNow();
        pristine.close();
        writeSets.close();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Estimated bytes held by the terrain and write-set caches (diagnostics and tests). */
    public long[] cacheBytes() {
        return new long[]{ pristine.weight(), writeSets.weight() };
    }

    /**
     * If this chunk's undecorated terrain is already cached (computed for a neighbour's decoration),
     * copy it into {@code target} and return true, so the platform doesn't generate it a second time.
     */
    public boolean fillFromCache(ProtoChunk target, int cx, int cz) {
        PristineChunk c = pristine.peek(key(cx, cz));
        if(c == null) return false;
        c.copyTo(target);
        return true;
    }

    public final class Capture implements ProtoChunk {
        private final ProtoChunk target;
        private final PristineChunk copy = new PristineChunk(minY, maxY, air);
        private final int cx, cz;

        private Capture(ProtoChunk target, int cx, int cz) {
            this.target = target;
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public void setBlock(int x, int y, int z, @NotNull BlockState state) {
            target.setBlock(x, y, z, state);
            copy.setBlock(x, y, z, state);
        }

        @Override
        public @NotNull BlockState getBlock(int x, int y, int z) {
            return target.getBlock(x, y, z);
        }

        @Override
        public int getMaxHeight() {
            return target.getMaxHeight();
        }

        @Override
        public Object getHandle() {
            return target.getHandle();
        }

        public void commit() {
            pristine.put(key(cx, cz), copy);
        }
    }

    WriteSet writeSet(int cx, int cz) {
        return writeSets.get(key(cx, cz), k -> {
            RecordingWorld view = new RecordingWorld(cx, cz);
            for(GenerationStage stage : stages) stage.populate(view);
            return view.toWriteSet();
        });
    }

    /** Decorate the centre chunk of {@code live}, writing only inside that chunk. */
    private static final boolean STATS = Boolean.getBoolean("terra.decoration.stats");
    private final java.util.concurrent.atomic.AtomicLong decorated = new java.util.concurrent.atomic.AtomicLong();

    public void decorate(ProtoWorld live) {
        if(STATS && decorated.incrementAndGet() % 1024 == 0) {
            LOGGER.info("[decoration-stats] decorated {} chunks; terrain cache {} MB, write-set cache {} MB, prefetch backlog {}",
                decorated.get(), pristine.weight() >> 20, writeSets.weight() >> 20, prefetchBacklog());
        }
        int cx = live.centerChunkX(), cz = live.centerChunkZ();
        Map<Long, WriteSet.Write> merged = new HashMap<>();
        List<WriteSet.Deferred> deferred = new ArrayList<>();
        for(int[] d : NEIGHBOURS_OWNER_LAST) {
            WriteSet ws = writeSet(cx + d[0], cz + d[1]);
            ws.into(cx, cz, merged, deferred);
        }
        PristineChunk own = pristine(cx, cz);
        int x0 = cx << 4, z0 = cz << 4;
        Set<WriteSet.Write> applied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Map.Entry<Long, WriteSet.Write> e : merged.entrySet()) {
            long p = e.getKey();
            int x = posX(p), y = posY(p), z = posZ(p);
            BlockState before = own.getBlock(x - x0, y, z - z0);
            BlockState current = live.getBlockState(x, y, z);
            if(!same(current, before)) continue; // changed by the server itself (vanilla structure etc.)
            live.setBlockState(x, y, z, e.getValue().state(), e.getValue().physics());
            applied.add(e.getValue());
        }
        for(WriteSet.Deferred d : deferred) {
            if(!shouldRun(d, merged.get(pos(d.x(), d.y(), d.z())), applied, live)) continue;
            try {
                d.op().accept(live);
            } catch(RuntimeException ex) {
                LOGGER.debug("Deferred decoration operation at ({}, {}, {}) failed", d.x(), d.y(), d.z(), ex);
            }
        }
    }

    /**
     * A deferred operation (loot, block-entity state, entity) belongs to the block its feature left at
     * that position. It runs only if that block is what the chunk ended up with:
     * <ul>
     *   <li>tied to a write: that exact write won the arbitration, was applied, and is still there;</li>
     *   <li>no write at the position (e.g. an entity in air): no feature of any chunk wrote there and the
     *       live block is still what the feature saw.</li>
     * </ul>
     */
    static boolean shouldRun(WriteSet.Deferred d, WriteSet.Write winner, Set<WriteSet.Write> applied, ProtoWorld live) {
        BlockState now = live.getBlockState(d.x(), d.y(), d.z());
        if(d.write() != null) return winner == d.write() && applied.contains(d.write()) && same(now, d.write().state());
        return winner == null && same(now, d.expected());
    }

    private static boolean same(BlockState a, BlockState b) {
        // Platform handles compare exact states cheaply (Bukkit BlockData.equals, CLI state string).
        if(a == b) return true;
        Object ha = a.getHandle(), hb = b.getHandle();
        if(ha != null && hb != null && ha != a && hb != b) return ha.equals(hb);
        return a.getAsString(true).equals(b.getAsString(true));
    }

    /** Private view for computing W(X): pristine terrain of X+-1 plus X's own writes. */
    private final class RecordingWorld implements ProtoWorld, DeferringWorld {
        private final int cx, cz;
        private final Map<Long, BlockState> overlay = new HashMap<>();
        private final Set<Long> physics = new HashSet<>();
        private final List<WriteSet.Deferred> deferred = new ArrayList<>();
        private final PristineChunk[] around = new PristineChunk[9];

        RecordingWorld(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        private boolean inRegion(int x, int y, int z) {
            int chx = x >> 4, chz = z >> 4;
            return chx >= cx - 1 && chx <= cx + 1 && chz >= cz - 1 && chz <= cz + 1 && y >= minY && y < maxY;
        }

        private PristineChunk chunk(int x, int z) {
            int i = ((x >> 4) - cx + 1) * 3 + ((z >> 4) - cz + 1);
            PristineChunk c = around[i];
            if(c == null) c = around[i] = pristine(x >> 4, z >> 4);
            return c;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            if(!inRegion(x, y, z)) return air;
            BlockState o = overlay.get(pos(x, y, z));
            return o != null ? o : chunk(x, z).getBlock(x & 15, y, z & 15);
        }

        @Override
        public void setBlockState(int x, int y, int z, BlockState data, boolean phys) {
            if(!inRegion(x, y, z)) return;
            long p = pos(x, y, z);
            overlay.put(p, data);
            if(phys) physics.add(p);
            else physics.remove(p);
        }

        @Override
        public void defer(int x, int y, int z, Consumer<WritableWorld> operation) {
            // Remember the block the feature sees at the target now; the operation is tied to it.
            if(inRegion(x, y, z)) deferred.add(new WriteSet.Deferred(x, y, z, operation, null, getBlockState(x, y, z)));
        }

        @Override
        public Entity spawnEntity(double x, double y, double z, EntityType entityType) {
            defer((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), w -> w.spawnEntity(x, y, z, entityType));
            return null;
        }

        @Override
        public BlockEntity getBlockEntity(int x, int y, int z) {
            return null; // block-entity work is deferred (see DeferringWorld)
        }

        WriteSet toWriteSet() {
            return WriteSet.of(overlay, physics, deferred);
        }

        @Override public long getSeed() { return seed; }
        @Override public int getMaxHeight() { return maxY; }
        @Override public int getMinHeight() { return minY; }
        @Override public ChunkGenerator getGenerator() { return generator; }
        @Override public BiomeProvider getBiomeProvider() { return biomes; }
        @Override public ConfigPack getPack() { return pack; }
        @Override public int centerChunkX() { return cx; }
        @Override public int centerChunkZ() { return cz; }
        @Override public ServerWorld getWorld() { return serverWorld; }
        @Override public Object getHandle() { return this; }
    }

    /** A chunk's decoration result, bucketed by the chunk each write lands in. */
    static final class WriteSet {
        /** One final block write. Compared by identity: two chunks writing the same state are different writes. */
        static final class Write {
            private final BlockState state;
            private final boolean physics;

            Write(BlockState state, boolean physics) {
                this.state = state;
                this.physics = physics;
            }

            BlockState state() { return state; }

            boolean physics() { return physics; }
        }

        /**
         * @param write    this chunk's final write at the target, or null if it wrote nothing there
         * @param expected the block the feature saw at the target when it deferred the operation
         */
        record Deferred(int x, int y, int z, Consumer<WritableWorld> op, Write write, BlockState expected) {}

        private final Map<Long, Map<Long, Write>> byChunk = new HashMap<>();
        private final Map<Long, List<Deferred>> deferredByChunk = new HashMap<>();

        static WriteSet of(Map<Long, BlockState> overlay, Set<Long> physics, List<Deferred> deferred) {
            WriteSet ws = new WriteSet();
            Map<Long, Write> all = new HashMap<>();
            for(Map.Entry<Long, BlockState> e : overlay.entrySet()) {
                long p = e.getKey();
                Write w = new Write(e.getValue(), physics.contains(p));
                all.put(p, w);
                ws.byChunk.computeIfAbsent(key(posX(p) >> 4, posZ(p) >> 4), k -> new HashMap<>()).put(p, w);
            }
            for(Deferred d : deferred) {
                Write w = all.get(pos(d.x(), d.y(), d.z()));
                // The feature overwrote its own target after deferring: the operation is stale.
                if(w != null && !same(w.state(), d.expected())) continue;
                ws.deferredByChunk.computeIfAbsent(key(d.x() >> 4, d.z() >> 4), k -> new ArrayList<>())
                    .add(new Deferred(d.x(), d.y(), d.z(), d.op(), w, d.expected()));
            }
            return ws;
        }

        /** Conservative heap estimate: per write a HashMap node, a boxed key and a Write; per operation ~96 bytes. */
        long estimatedBytes() {
            long n = 0, d = 0;
            for(Map<Long, Write> m : byChunk.values()) n += m.size();
            for(List<Deferred> l : deferredByChunk.values()) d += l.size();
            return 256 + (byChunk.size() + deferredByChunk.size()) * 128L + n * 96 + d * 96;
        }

        void into(int cx, int cz, Map<Long, Write> merged, List<Deferred> deferred) {
            Map<Long, Write> w = byChunk.get(key(cx, cz));
            if(w != null) merged.putAll(w);
            List<Deferred> d = deferredByChunk.get(key(cx, cz));
            if(d != null) deferred.addAll(d);
        }
    }

    /**
     * Map of computed-once values bounded by estimated bytes, least recently used first (concurrent
     * callers share one computation). Entries can be reserved for background computation; a cancelled
     * reservation is retried by real callers.
     */
    private static final class LruFutures<V> {
        private final long maxBytes;
        private final java.util.function.ToLongFunction<V> weigher;
        private final ConcurrentHashMap<Long, CompletableFuture<V>> map = new ConcurrentHashMap<>();
        // Completed entries in access order with their weights; guarded by itself.
        private final java.util.LinkedHashMap<Long, Long> lru = new java.util.LinkedHashMap<>(256, 0.75f, true);
        private long total;
        private volatile boolean closed;

        LruFutures(long maxBytes, java.util.function.ToLongFunction<V> weigher) {
            this.maxBytes = Math.max(1 << 20, maxBytes);
            this.weigher = weigher;
        }

        boolean contains(long k) {
            return map.containsKey(k);
        }

        V peek(long k) {
            CompletableFuture<V> f = map.get(k);
            if(f == null || !f.isDone() || f.isCompletedExceptionally()) return null;
            touch(k);
            return f.join();
        }

        /**
         * A reservation for background computation. Whoever claims it first computes it: the queued
         * task when it starts, or a caller that needs the value before then. So a caller never waits
         * for work that is only queued, only for work that is actually running.
         */
        static final class Pending<V> extends CompletableFuture<V> {
            private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

            boolean claim() {
                return claimed.compareAndSet(false, true);
            }
        }

        /** Install a reservation for {@code k}; returns it if this call reserved k, else null. */
        CompletableFuture<V> reserve(long k) {
            if(closed) return null;
            Pending<V> mine = new Pending<>();
            return map.putIfAbsent(k, mine) == null ? mine : null;
        }

        /** Background completion of a reservation (skipped if cancelled, claimed by a caller or done). */
        void completeReserved(long k, CompletableFuture<V> f, Function<Long, V> compute) {
            if(closed || f.isDone() || !(f instanceof Pending<V> p) || !p.claim()) return;
            computeInto(k, f, compute);
        }

        private V computeInto(long k, CompletableFuture<V> f, Function<Long, V> compute) {
            try {
                V v = compute.apply(k);
                if(f.complete(v)) admitted(k, f, v);
                return v;
            } catch(RuntimeException | Error t) {
                map.remove(k, f);
                f.completeExceptionally(t);
                throw t;
            }
        }

        void cancel(long k, CompletableFuture<V> f) {
            map.remove(k, f);
            f.cancel(false);
        }

        /** Store a value computed elsewhere (also completes a pending reservation for k). */
        void put(long k, V v) {
            if(closed) return;
            CompletableFuture<V> done = CompletableFuture.completedFuture(v);
            CompletableFuture<V> prev = map.putIfAbsent(k, done);
            if(prev == null) admitted(k, done, v);
            else {
                if(prev instanceof Pending<V> p) p.claim(); // the queued task needn't compute it any more
                if(prev.complete(v)) admitted(k, prev, v);
            }
        }

        V get(long k, Function<Long, V> compute) {
            if(closed) return compute.apply(k);
            while(true) {
                CompletableFuture<V> mine = new CompletableFuture<>();
                CompletableFuture<V> f = map.putIfAbsent(k, mine);
                if(f == null) {
                    try {
                        V v = compute.apply(k);
                        mine.complete(v);
                        admitted(k, mine, v);
                        return v;
                    } catch(RuntimeException | Error t) {
                        map.remove(k, mine);
                        mine.completeExceptionally(t);
                        throw t;
                    }
                }
                if(f instanceof Pending<V> p && p.claim()) {
                    return computeInto(k, f, compute); // queued but not started: do it here instead of waiting
                }
                try {
                    V v = f.join();
                    touch(k);
                    return v;
                } catch(java.util.concurrent.CancellationException cancelled) {
                    map.remove(k, f); // a dropped prefetch reservation: compute it here
                }
            }
        }

        private void touch(long k) {
            synchronized(lru) {
                lru.get(k);
            }
        }

        private void admitted(long k, CompletableFuture<V> f, V v) {
            long w = Math.max(1, weigher.applyAsLong(v));
            synchronized(lru) {
                if(closed || map.get(k) != f) return; // evicted or replaced meanwhile
                Long old = lru.put(k, w);
                total += w - (old == null ? 0 : old);
                var it = lru.entrySet().iterator();
                while(total > maxBytes && lru.size() > 1 && it.hasNext()) {
                    var e = it.next();
                    if(e.getKey() == k) continue;
                    total -= e.getValue();
                    map.remove(e.getKey());
                    it.remove();
                }
            }
        }

        long weight() {
            synchronized(lru) {
                return total;
            }
        }

        void close() {
            closed = true;
            map.values().forEach(f -> f.cancel(false));
            map.clear();
            synchronized(lru) {
                lru.clear();
                total = 0;
            }
        }
    }
}
