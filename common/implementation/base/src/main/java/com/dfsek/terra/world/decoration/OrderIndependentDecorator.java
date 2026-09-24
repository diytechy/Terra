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
 * Enable with {@code -Dterra.decoration.orderIndependent=true}. Cache sizes:
 * {@code terra.decoration.pristineCache} (chunks, default 1024) and
 * {@code terra.decoration.writeSetCache} (chunks, default 512).
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
        this.pristine = new LruFutures<>(Integer.getInteger("terra.decoration.pristineCache", 1024));
        this.writeSets = new LruFutures<>(Integer.getInteger("terra.decoration.writeSetCache", 512));
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
        return pristine.get(key(cx, cz), k -> {
            PristineChunk c = new PristineChunk(minY, maxY, air);
            generator.generateChunkData(c, properties, biomes, cx, cz);
            return c;
        });
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
    public void decorate(ProtoWorld live) {
        int cx = live.centerChunkX(), cz = live.centerChunkZ();
        Map<Long, WriteSet.Write> merged = new HashMap<>();
        List<WriteSet.Deferred> deferred = new ArrayList<>();
        for(int[] d : NEIGHBOURS_OWNER_LAST) {
            WriteSet ws = writeSet(cx + d[0], cz + d[1]);
            ws.into(cx, cz, merged, deferred);
        }
        PristineChunk own = pristine(cx, cz);
        int x0 = cx << 4, z0 = cz << 4;
        for(Map.Entry<Long, WriteSet.Write> e : merged.entrySet()) {
            long p = e.getKey();
            int x = posX(p), y = posY(p), z = posZ(p);
            BlockState before = own.getBlock(x - x0, y, z - z0);
            BlockState current = live.getBlockState(x, y, z);
            if(!same(current, before)) continue; // changed by the server itself (vanilla structure etc.)
            live.setBlockState(x, y, z, e.getValue().state(), e.getValue().physics());
        }
        for(WriteSet.Deferred d : deferred) {
            try {
                d.op().accept(live);
            } catch(RuntimeException ex) {
                LOGGER.debug("Deferred decoration operation at ({}, {}, {}) failed", d.x(), d.y(), d.z(), ex);
            }
        }
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
            if(inRegion(x, y, z)) deferred.add(new WriteSet.Deferred(x, y, z, operation));
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
        record Write(BlockState state, boolean physics) {}

        record Deferred(int x, int y, int z, Consumer<WritableWorld> op) {}

        private final Map<Long, Map<Long, Write>> byChunk = new HashMap<>();
        private final Map<Long, List<Deferred>> deferredByChunk = new HashMap<>();

        static WriteSet of(Map<Long, BlockState> overlay, Set<Long> physics, List<Deferred> deferred) {
            WriteSet ws = new WriteSet();
            for(Map.Entry<Long, BlockState> e : overlay.entrySet()) {
                long p = e.getKey();
                ws.byChunk.computeIfAbsent(key(posX(p) >> 4, posZ(p) >> 4), k -> new HashMap<>())
                    .put(p, new Write(e.getValue(), physics.contains(p)));
            }
            for(Deferred d : deferred) {
                ws.deferredByChunk.computeIfAbsent(key(d.x() >> 4, d.z() >> 4), k -> new ArrayList<>()).add(d);
            }
            return ws;
        }

        void into(int cx, int cz, Map<Long, Write> merged, List<Deferred> deferred) {
            Map<Long, Write> w = byChunk.get(key(cx, cz));
            if(w != null) merged.putAll(w);
            List<Deferred> d = deferredByChunk.get(key(cx, cz));
            if(d != null) deferred.addAll(d);
        }
    }

    /** Bounded map of computed-once values (concurrent callers share one computation). */
    private static final class LruFutures<V> {
        private final int max;
        private final ConcurrentHashMap<Long, CompletableFuture<V>> map = new ConcurrentHashMap<>();
        private final ArrayDeque<Long> order = new ArrayDeque<>();

        LruFutures(int max) {
            this.max = Math.max(16, max);
        }

        V peek(long k) {
            CompletableFuture<V> f = map.get(k);
            return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
        }

        void put(long k, V v) {
            if(map.putIfAbsent(k, CompletableFuture.completedFuture(v)) == null) {
                synchronized(order) {
                    order.addLast(k);
                    while(order.size() > max) map.remove(order.pollFirst());
                }
            }
        }

        V get(long k, Function<Long, V> compute) {
            CompletableFuture<V> mine = new CompletableFuture<>();
            CompletableFuture<V> f = map.putIfAbsent(k, mine);
            if(f == null) {
                f = mine;
                try {
                    mine.complete(compute.apply(k));
                } catch(Throwable t) {
                    map.remove(k, mine);
                    mine.completeExceptionally(t);
                }
                synchronized(order) {
                    order.addLast(k);
                    while(order.size() > max) map.remove(order.pollFirst());
                }
            }
            return f.join();
        }
    }
}
