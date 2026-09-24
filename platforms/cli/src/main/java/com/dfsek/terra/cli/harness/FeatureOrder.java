package com.dfsek.terra.cli.harness;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.entity.Entity;
import com.dfsek.terra.api.entity.EntityType;
import com.dfsek.terra.api.registry.key.StringIdentifiable;
import com.dfsek.terra.api.world.ServerWorld;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;
import com.dfsek.terra.api.world.chunk.generation.stage.GenerationStage;
import com.dfsek.terra.cli.handle.CLIWorldHandle;
import com.dfsek.terra.cli.world.CLIWorld;
import com.dfsek.terra.cli.world.chunk.CLIChunk;


/**
 * Server-like feature decoration in the CLI, to test whether Terra's feature stages depend on the
 * order in which chunks are decorated.
 * <p>
 * Model of the Paper/vanilla FEATURES step: all terrain in the area (targets plus a margin) is
 * generated first; then each target chunk is decorated once, in a given order, through a proto-world
 * limited to that chunk and its 8 neighbours (reads outside return air, writes outside are dropped),
 * like Bukkit's LimitedRegion. Blocks written into a neighbour stay there, so a chunk decorated later
 * sees its neighbours' features. The same area is decorated in two orders and the target chunks'
 * blocks are compared. Single-threaded and deterministic: any difference is order dependence of the
 * pack's stages alone (no vanilla decoration, no threads).
 */
@Command(name = "feature-order", description = "Decorate the same chunks in two orders and compare (Terra stages only).")
final class FeatureOrder extends TerraceHarness.Common implements Callable<Integer> {
    @Option(names = "--cx", description = "Min target chunk X.") int cx = 185;
    @Option(names = "--cz", description = "Min target chunk Z.") int cz = 185;
    @Option(names = "--size", description = "Target chunks per side.") int size = 6;
    @Option(names = "--orders", split = ",", description = "Two orders out of rows, reverse, spiral, random.")
    List<String> orders = new ArrayList<>(List.of("rows", "reverse"));
    @Option(names = "--stages", split = ",", description = "Only run these stage IDs (default: all).") List<String> stages;
    @Option(names = "--radius", description = "Proto-world radius in chunks around the decorated chunk.") int radius = 1;
    @Option(names = "--order-independent", description = "Decorate with OrderIndependentDecorator.") boolean oi = false;
    @Option(names = "--save", description = "Write the first order's decorated world to <dir>/region.") java.io.File save;
    @Option(names = "--parallel", description = "Decoration threads for the orders named par-<order> (chunks whose regions "
                                                  + "overlap never run at the same time).") int parallel = 7;

    @Override
    public Integer call() throws Exception {
        ConfigPack p = loadPack();
        List<GenerationStage> st = new ArrayList<>();
        for(GenerationStage s : p.getStages()) {
            String id = s instanceof StringIdentifiable si ? si.getID() : s.toString();
            if(stages == null || stages.contains(id)) st.add(s);
        }
        System.out.println("FEATURE-ORDER stages: " + st.stream().map(s -> s instanceof StringIdentifiable si ? si.getID() : "?").toList());
        CLIWorld a = decorate(p, st, orders.get(0));
        if(save != null) a.save(new java.io.File(save, "region"));
        CLIWorld b = decorate(p, st, orders.get(1));
        Map<String, Integer> pairs = new TreeMap<>();
        int chunks = 0, blocks = 0;
        int edge = 0, interior = 0;
        List<String> examples = new ArrayList<>();
        for(int x = cx; x < cx + size; x++) {
            for(int z = cz; z < cz + size; z++) {
                CLIChunk ca = a.peekChunk(x, z), cb = b.peekChunk(x, z);
                boolean diff = false;
                for(int lx = 0; lx < 16; lx++) {
                    for(int lz = 0; lz < 16; lz++) {
                        for(int y = minHeight; y < maxHeight; y++) {
                            String sa = ca.getBlock(lx, y, lz).getAsString(false);
                            String sb = cb.getBlock(lx, y, lz).getAsString(false);
                            if(sa.equals(sb)) continue;
                            diff = true;
                            blocks++;
                            if(lx < 2 || lx > 13 || lz < 2 || lz > 13) edge++;
                            else interior++;
                            pairs.merge(sa + " / " + sb, 1, Integer::sum);
                            if(examples.size() < 12) examples.add((x * 16 + lx) + " " + y + " " + (z * 16 + lz) + ": " + sa + " / " + sb);
                        }
                    }
                }
                if(diff) chunks++;
            }
        }
        System.out.printf("FEATURE-ORDER %s vs %s: chunks %d/%d differ, blocks %d (edge band %d, interior %d)%n", orders.get(0),
            orders.get(1), chunks, size * size, blocks, edge, interior);
        pairs.entrySet().stream().sorted((u, v) -> v.getValue() - u.getValue()).limit(10)
            .forEach(e -> System.out.println("FEATURE-ORDER   " + e.getValue() + "  " + e.getKey()));
        examples.forEach(e -> System.out.println("FEATURE-ORDER   at " + e));
        return chunks == 0 ? 0 : 1;
    }

    private CLIWorld decorate(ConfigPack p, List<GenerationStage> st, String order) {
        CLIWorld w = new CLIWorld(seed, maxHeight, minHeight, p, false, threads);
        int m = radius + 1;
        w.generate(cx - m, cz - m, size + 2 * m, size + 2 * m, false);
        List<int[]> coords = new ArrayList<>();
        for(int x = cx; x < cx + size; x++) for(int z = cz; z < cz + size; z++) coords.add(new int[]{ x, z });
        switch(order.startsWith("par-") ? order.substring(4) : order) {
            case "reverse" -> Collections.reverse(coords);
            case "random" -> Collections.shuffle(coords, new Random(99));
            case "spiral" -> {
                double mx = cx + size / 2.0, mz = cz + size / 2.0;
                coords.sort(java.util.Comparator.comparingDouble(
                        (int[] c) -> Math.max(Math.abs(c[0] + 0.5 - mx), Math.abs(c[1] + 0.5 - mz)))
                    .thenComparingDouble(c -> Math.atan2(c[1] + 0.5 - mz, c[0] + 0.5 - mx)));
            }
            default -> { }
        }
        com.dfsek.terra.world.decoration.OrderIndependentDecorator[] oiDec = { null };
        if(oi) {
            oiDec[0] = new com.dfsek.terra.world.decoration.OrderIndependentDecorator(p, st, w.getGenerator(),
                CLIWorldHandle.getAIR(), new BoundedProtoWorld(w, p.getBiomeProvider(), cx, cz, radius));
        }
        java.util.function.Consumer<int[]> decorateOne = c -> {
            BoundedProtoWorld region = new BoundedProtoWorld(w, p.getBiomeProvider(), c[0], c[1], radius);
            if(oiDec[0] != null) oiDec[0].decorate(region);
            else for(GenerationStage s : st) s.populate(region);
        };
        if(!order.startsWith("par-")) {
            long t0 = System.nanoTime();
            coords.forEach(decorateOne);
            System.out.printf("FEATURE-ORDER TIMING %s decoration of %d chunks: %.2f s%n", order, coords.size(), (System.nanoTime() - t0) / 1e9);
            return w;
        }
        // Parallel: take chunks in list order; a chunk may start when no running chunk is within
        // 2*radius (their regions don't overlap), as a server's chunk system guarantees for writes.
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(parallel);
        java.util.List<int[]> running = new java.util.ArrayList<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>(coords);
        Object lock = new Object();
        java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        while(!queue.isEmpty()) {
            int[] next = null;
            synchronized(lock) {
                for(int[] c : queue) {
                    boolean free = running.stream().noneMatch(r -> Math.abs(r[0] - c[0]) <= 2 * radius && Math.abs(r[1] - c[1]) <= 2 * radius);
                    // Keep the list order among mutually overlapping chunks (only skip ahead past blocked ones
                    // whose regions don't overlap the candidate).
                    boolean blockedBehind = false;
                    for(int[] q : queue) {
                        if(q == c) break;
                        if(Math.abs(q[0] - c[0]) <= 2 * radius && Math.abs(q[1] - c[1]) <= 2 * radius) { blockedBehind = true; break; }
                    }
                    if(free && !blockedBehind && running.size() < parallel) { next = c; break; }
                }
                if(next != null) {
                    queue.remove(next);
                    running.add(next);
                } else {
                    try { lock.wait(5); } catch(InterruptedException e) { throw new RuntimeException(e); }
                }
            }
            if(next != null) {
                int[] c = next;
                fs.add(ex.submit(() -> {
                    try {
                        decorateOne.accept(c);
                    } finally {
                        synchronized(lock) { running.remove(c); lock.notifyAll(); }
                    }
                    return null;
                }));
            }
        }
        try { for(var f : fs) f.get(); } catch(Exception e) { throw new RuntimeException(e); }
        ex.shutdown();
        return w;
    }

    /** A proto-world limited to (centre chunk +- radius), like Bukkit's LimitedRegion. */
    static final class BoundedProtoWorld implements ProtoWorld {
        private final CLIWorld w;
        private final BiomeProvider biomes;
        private final int ccx, ccz, r;

        BoundedProtoWorld(CLIWorld w, BiomeProvider biomes, int ccx, int ccz, int r) {
            this.w = w;
            this.biomes = biomes;
            this.ccx = ccx;
            this.ccz = ccz;
            this.r = r;
        }

        private boolean in(int x, int y, int z) {
            int chx = Math.floorDiv(x, 16), chz = Math.floorDiv(z, 16);
            return Math.abs(chx - ccx) <= r && Math.abs(chz - ccz) <= r && y >= w.getMinHeight() && y < w.getMaxHeight();
        }

        @Override public Object getHandle() { return this; }
        @Override public BlockState getBlockState(int x, int y, int z) {
            return in(x, y, z) ? w.getBlockState(x, y, z) : CLIWorldHandle.getAIR();
        }
        @Override public BlockEntity getBlockEntity(int x, int y, int z) { return w.getBlockEntity(x, y, z); }
        @Override public long getSeed() { return w.getSeed(); }
        @Override public int getMaxHeight() { return w.getMaxHeight(); }
        @Override public int getMinHeight() { return w.getMinHeight(); }
        @Override public ChunkGenerator getGenerator() { return w.getGenerator(); }
        @Override public BiomeProvider getBiomeProvider() { return biomes; }
        @Override public com.dfsek.terra.api.config.ConfigPack getPack() { return w.getPack(); }
        @Override public void setBlockState(int x, int y, int z, BlockState data, boolean physics) {
            if(in(x, y, z)) w.setBlockState(x, y, z, data, physics);
        }
        @Override public Entity spawnEntity(double x, double y, double z, EntityType entityType) { return null; }
        @Override public int centerChunkX() { return ccx; }
        @Override public int centerChunkZ() { return ccz; }
        @Override public ServerWorld getWorld() { return w; }
    }
}
