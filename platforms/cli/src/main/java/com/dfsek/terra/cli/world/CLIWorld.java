package com.dfsek.terra.cli.world;

import com.dfsek.seismic.type.vector.Vector3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.entity.Entity;
import com.dfsek.terra.api.entity.EntityType;
import com.dfsek.terra.api.world.ServerWorld;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;
import com.dfsek.terra.cli.world.chunk.CLIChunk;


/**
 * A CLI world that generates an arbitrary rectangle of chunks, anywhere in the world
 * (negative coordinates included), and writes it as modern (1.18+) Anvil region files.
 * <p>
 * Chunks are stored by absolute chunk coordinate. The previous implementation indexed
 * regions with two different strides and only covered the positive quadrant from the origin.
 */
public class CLIWorld implements ServerWorld {
    private static final Logger LOGGER = LoggerFactory.getLogger(CLIWorld.class);

    private final Map<Long, CLIChunk> chunks = new ConcurrentHashMap<>();
    private final long seed;
    private final int maxHeight;
    private final int minHeight;
    private final ChunkGenerator chunkGenerator;
    private final BiomeProvider biomeProvider;
    private final ConfigPack pack;
    private final boolean noSave;
    private final int threads;

    public CLIWorld(long seed, int maxHeight, int minHeight, ConfigPack pack, boolean noSave, int threads) {
        this.maxHeight = maxHeight;
        this.minHeight = minHeight;
        this.seed = seed;
        this.chunkGenerator = pack.getGeneratorProvider().newInstance(pack);
        this.biomeProvider = pack.getBiomeProvider();
        this.pack = pack;
        this.noSave = noSave;
        this.threads = Math.max(1, threads);
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Generate every chunk in [originX, originX + width) x [originZ, originZ + depth), in chunk coordinates.
     *
     * @param features run the pack's population stages (features, structures) as well as the terrain.
     */
    public void generate(int originX, int originZ, int width, int depth, boolean features) {
        generate(originX, originZ, width, depth, features, "rows");
    }

    /**
     * @param order chunk submission order: {@code rows} (x-major), {@code spiral} (from the centre
     *              outward, like a server loading chunks around a player) or {@code random} (seeded).
     */
    public void generate(int originX, int originZ, int width, int depth, boolean features, String order) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        long start = System.nanoTime();
        int total = width * depth;
        List<int[]> coords = new ArrayList<>();
        for(int x = originX; x < originX + width; x++) {
            for(int z = originZ; z < originZ + depth; z++) coords.add(new int[]{ x, z });
        }
        if("spiral".equals(order)) {
            double mx = originX + width / 2.0, mz = originZ + depth / 2.0;
            coords.sort(java.util.Comparator.comparingDouble((int[] c) -> Math.max(Math.abs(c[0] + 0.5 - mx), Math.abs(c[1] + 0.5 - mz)))
                .thenComparingDouble(c -> Math.atan2(c[1] + 0.5 - mz, c[0] + 0.5 - mx)));
        } else if("random".equals(order)) {
            java.util.Collections.shuffle(coords, new java.util.Random(1234));
        }
        for(int[] c : coords) {
            {
                int cx = c[0], cz = c[1];
                futures.add(executor.submit(() -> {
                    CLIChunk chunk = noSave ? new CLIChunk(cx, cz, this) : getChunkAt(cx, cz);
                    chunkGenerator.generateChunkData(chunk, this, biomeProvider, cx, cz);
                    if(features) {
                        CLIProtoWorld protoWorld = new CLIProtoWorld(this, biomeProvider, cx, cz);
                        pack.getStages().forEach(stage -> stage.populate(protoWorld));
                    }
                    int n = done.incrementAndGet();
                    if(n % 256 == 0) {
                        double secs = (System.nanoTime() - start) / 1e9;
                        LOGGER.info("Generated {}/{} chunks ({} cps)", n, total, String.format("%.1f", n / secs));
                    }
                    return null;
                }));
            }
        }
        try {
            for(Future<?> future : futures) {
                future.get();
            }
        } catch(InterruptedException | ExecutionException e) {
            throw new RuntimeException("Chunk generation failed", e);
        } finally {
            executor.shutdown();
        }
        double secs = (System.nanoTime() - start) / 1e9;
        LOGGER.info("Generated {} chunks in {}s ({} cps)", total, String.format("%.1f", secs),
            String.format("%.1f", total / secs));
    }

    /**
     * Write every stored chunk to {@code regionDir/r.X.Z.mca}; chunks already in those files and not
     * stored here are kept.
     */
    public void save(File regionDir, int dataVersion, String status) throws IOException {
        if(!regionDir.exists() && !regionDir.mkdirs()) throw new IOException("Cannot create " + regionDir);
        Map<Long, Map<Integer, CLIChunk>> byRegion = new HashMap<>();
        for(CLIChunk chunk : chunks.values()) {
            int rx = Math.floorDiv(chunk.getX(), 32), rz = Math.floorDiv(chunk.getZ(), 32);
            int index = Math.floorMod(chunk.getX(), 32) + 32 * Math.floorMod(chunk.getZ(), 32);
            byRegion.computeIfAbsent(key(rx, rz), k -> new HashMap<>()).put(index, chunk);
        }
        for(Map.Entry<Long, Map<Integer, CLIChunk>> e : byRegion.entrySet()) {
            int rx = (int) (e.getKey() >> 32), rz = (int) (long) e.getKey();
            File file = new File(regionDir, "r." + rx + "." + rz + ".mca");
            AnvilWriter.writeRegion(file, e.getValue(), minHeight, maxHeight, dataVersion, status);
            LOGGER.info("Wrote {} chunks to {}", e.getValue().size(), file);
        }
    }

    /** Drop all stored chunks (to generate the next area with bounded memory). */
    public void clear() {
        chunks.clear();
    }

    public CLIChunk peekChunk(int x, int z) {
        return chunks.get(key(x, z));
    }

    @Override
    public Object getHandle() {
        return this;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        return getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
            .getBlock(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
    }

    @Override
    public BlockEntity getBlockEntity(int x, int y, int z) {
        return new BlockEntity() {
            @Override
            public boolean update(boolean applyPhysics) {
                return false;
            }

            @Override
            public Vector3 getPosition() {
                return Vector3.of(x, y, z);
            }

            @Override
            public int getX() {
                return x;
            }

            @Override
            public int getY() {
                return y;
            }

            @Override
            public int getZ() {
                return z;
            }

            @Override
            public BlockState getBlockState() {
                return CLIWorld.this.getBlockState(x, y, z);
            }

            @Override
            public Object getHandle() {
                return this;
            }
        };
    }

    @Override
    public CLIChunk getChunkAt(int x, int z) {
        return chunks.computeIfAbsent(key(x, z), k -> new CLIChunk(x, z, this));
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public int getMinHeight() {
        return minHeight;
    }

    @Override
    public ChunkGenerator getGenerator() {
        return chunkGenerator;
    }

    @Override
    public BiomeProvider getBiomeProvider() {
        return biomeProvider;
    }

    @Override
    public ConfigPack getPack() {
        return pack;
    }

    @Override
    public void setBlockState(int x, int y, int z, BlockState data, boolean physics) {
        getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
            .setBlock(Math.floorMod(x, 16), y, Math.floorMod(z, 16), data, physics);
    }

    @Override
    public Entity spawnEntity(double x, double y, double z, EntityType entityType) {
        return null;
    }

    private static final class CLIProtoWorld implements ProtoWorld {
        private final CLIWorld delegate;
        private final BiomeProvider biomeProvider;
        private final int x, z;

        private CLIProtoWorld(CLIWorld delegate, BiomeProvider biomeProvider, int x, int z) {
            this.delegate = delegate;
            this.biomeProvider = biomeProvider;
            this.x = x;
            this.z = z;
        }

        @Override
        public Object getHandle() {
            return this;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            return delegate.getBlockState(x, y, z);
        }

        @Override
        public BlockEntity getBlockEntity(int x, int y, int z) {
            return delegate.getBlockEntity(x, y, z);
        }

        @Override
        public long getSeed() {
            return delegate.seed;
        }

        @Override
        public int getMaxHeight() {
            return delegate.maxHeight;
        }

        @Override
        public int getMinHeight() {
            return delegate.minHeight;
        }

        @Override
        public ChunkGenerator getGenerator() {
            return delegate.chunkGenerator;
        }

        @Override
        public BiomeProvider getBiomeProvider() {
            return biomeProvider;
        }

        @Override
        public ConfigPack getPack() {
            return delegate.pack;
        }

        @Override
        public void setBlockState(int x, int y, int z, BlockState data, boolean physics) {
            delegate.setBlockState(x, y, z, data, physics);
        }

        @Override
        public Entity spawnEntity(double x, double y, double z, EntityType entityType) {
            return delegate.spawnEntity(x, y, z, entityType);
        }

        @Override
        public int centerChunkX() {
            return x;
        }

        @Override
        public int centerChunkZ() {
            return z;
        }

        @Override
        public ServerWorld getWorld() {
            return delegate;
        }
    }
}
