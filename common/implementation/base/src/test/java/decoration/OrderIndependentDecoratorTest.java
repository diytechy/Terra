package decoration;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.dfsek.terra.api.block.BlockType;
import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.block.state.properties.Property;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.entity.Entity;
import com.dfsek.terra.api.entity.EntityType;
import com.dfsek.terra.api.world.ServerWorld;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.chunk.generation.ProtoChunk;
import com.dfsek.terra.api.world.chunk.generation.ProtoWorld;
import com.dfsek.terra.api.world.chunk.generation.stage.GenerationStage;
import com.dfsek.terra.api.world.chunk.generation.util.Palette;
import com.dfsek.terra.api.world.info.WorldProperties;
import com.dfsek.terra.world.decoration.OrderIndependentDecorator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;


/**
 * Server-like decoration without a pack: flat terrain, and one stage with the two mechanisms that made
 * CHIMERA's features order-dependent on Paper - features that write into neighbour chunks but only into
 * air ("trees"), and a feature whose locator reads neighbouring blocks, including blocks other chunks
 * wrote ("web"). Chunks are decorated one by one through 3x3-chunk regions, in two orders.
 */
public class OrderIndependentDecoratorTest {
    private static final int MIN = 0, MAX = 96, SIZE = 6;

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

    static final S AIR = new S("air"), STONE = new S("stone"), LEAF = new S("leaf"), LOG = new S("log"), CHEST = new S("chest"),
        VANILLA = new S("vanilla");

    /** Flat terrain: stone up to y=63. */
    static final ChunkGenerator FLAT = new ChunkGenerator() {
        @Override
        public void generateChunkData(@NotNull ProtoChunk chunk, @NotNull WorldProperties world, @NotNull BiomeProvider biomes, int cx,
                                      int cz) {
            for(int x = 0; x < 16; x++) for(int z = 0; z < 16; z++) for(int y = MIN; y < 64; y++) chunk.setBlock(x, y, z, STONE);
        }
        @Override public BlockState getBlock(WorldProperties world, int x, int y, int z, BiomeProvider b) { return y < 64 ? STONE : AIR; }
        @Override public Palette getPalette(int x, int y, int z, WorldProperties world, BiomeProvider b) { return null; }
    };

    /** Trees that fill only air (overlaps: first writer wins), then a web that grows next to leaves. */
    static final GenerationStage STAGE = world -> {
        int x0 = world.centerChunkX() << 4, z0 = world.centerChunkZ() << 4;
        for(int x = x0; x < x0 + 16; x++) {
            for(int z = z0; z < z0 + 16; z++) {
                long h = (x * 341873128712L + z * 132897987541L) ^ 0x5DEECE66DL;
                if(Math.floorMod(h, 7) != 0) continue;
                int top = 64 + Math.floorMod((int) (h >> 8), 3);
                for(int y = 64; y < top + 2; y++) if(world.getBlockState(x, y, z).isAir()) world.setBlockState(x, y, z, LOG);
                for(int dx = -3; dx <= 3; dx++) for(int dz = -3; dz <= 3; dz++) for(int y = top; y < top + 3; y++)
                    if(world.getBlockState(x + dx, y, z + dz).isAir()) world.setBlockState(x + dx, y, z + dz, LEAF);
            }
        }
        for(int x = x0; x < x0 + 16; x++) {
            for(int z = z0; z < z0 + 16; z++) {
                int y = 66;
                if(!world.getBlockState(x, y, z).isAir()) continue;
                boolean nextToLeaf = world.getBlockState(x - 1, y, z) == LEAF || world.getBlockState(x + 1, y, z) == LEAF
                                     || world.getBlockState(x, y, z - 1) == LEAF || world.getBlockState(x, y, z + 1) == LEAF;
                if(nextToLeaf && Math.floorMod(x * 7 + z * 13, 3) != 0) world.setBlockState(x, y, z, LEAF);
            }
        }
    };

    /** A map-backed world; every chunk in [-1, SIZE] has terrain. */
    static final class TestWorld {
        final Map<Long, BlockState> blocks = new HashMap<>();

        TestWorld() {
            for(int cx = -2; cx <= SIZE + 1; cx++) for(int cz = -2; cz <= SIZE + 1; cz++)
                for(int x = 0; x < 16; x++) for(int z = 0; z < 16; z++) for(int y = MIN; y < 64; y++)
                    blocks.put(key(cx * 16 + x, y, cz * 16 + z), STONE);
        }

        static long key(int x, int y, int z) {
            return (((long) x & 0xFFFFFL) << 40) | (((long) z & 0xFFFFFL) << 20) | (y & 0xFFFFF);
        }

        BlockState get(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), AIR);
        }

        ProtoWorld region(int cx, int cz) {
            ConfigPack pack = (ConfigPack) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ ConfigPack.class },
                (p, m, a) -> null);
            return new ProtoWorld() {
                boolean in(int x, int y, int z) {
                    return Math.abs((x >> 4) - cx) <= 1 && Math.abs((z >> 4) - cz) <= 1 && y >= MIN && y < MAX;
                }
                @Override public BlockState getBlockState(int x, int y, int z) { return in(x, y, z) ? get(x, y, z) : AIR; }
                @Override public void setBlockState(int x, int y, int z, BlockState s, boolean physics) {
                    if(in(x, y, z)) blocks.put(key(x, y, z), s);
                }
                @Override public BlockEntity getBlockEntity(int x, int y, int z) { return null; }
                @Override public Entity spawnEntity(double x, double y, double z, EntityType t) { return null; }
                @Override public int centerChunkX() { return cx; }
                @Override public int centerChunkZ() { return cz; }
                @Override public ServerWorld getWorld() { return null; }
                @Override public long getSeed() { return 1; }
                @Override public int getMaxHeight() { return MAX; }
                @Override public int getMinHeight() { return MIN; }
                @Override public ChunkGenerator getGenerator() { return FLAT; }
                @Override public BiomeProvider getBiomeProvider() { return null; }
                @Override public ConfigPack getPack() { return pack; }
                @Override public Object getHandle() { return this; }
            };
        }

        /** Blocks of the SIZE x SIZE decorated chunks, as a comparable string. */
        String snapshot() {
            StringBuilder sb = new StringBuilder();
            for(int x = 0; x < SIZE * 16; x++) for(int z = 0; z < SIZE * 16; z++) for(int y = 60; y < 80; y++)
                sb.append(switch(get(x, y, z).getAsString(true)) {
                    case "leaf" -> 'f';
                    case "log" -> 'g';
                    case "stone" -> 's';
                    default -> '.';
                });
            return sb.toString();
        }
    }

    static List<int[]> order(String name) {
        List<int[]> l = new ArrayList<>();
        for(int x = 0; x < SIZE; x++) for(int z = 0; z < SIZE; z++) l.add(new int[]{ x, z });
        if(name.equals("reverse")) Collections.reverse(l);
        if(name.equals("random")) Collections.shuffle(l, new Random(7));
        return l;
    }

    static String decorate(String order, boolean orderIndependent) {
        TestWorld w = new TestWorld();
        OrderIndependentDecorator dec = null;
        for(int[] c : order(order)) {
            ProtoWorld region = w.region(c[0], c[1]);
            if(orderIndependent) {
                if(dec == null) dec = new OrderIndependentDecorator(region.getPack(), List.of(STAGE), FLAT, AIR, region);
                dec.decorate(region);
            } else {
                STAGE.populate(region);
            }
        }
        return w.snapshot();
    }

    @Test
    public void plainDecorationDependsOnOrder() {
        // Guards the test itself: the synthetic stage must reproduce the problem.
        String a = decorate("rows", false), b = decorate("reverse", false);
        assertNotEquals(a, b);
    }

    @Test
    public void orderIndependentDecorationDoesNot() {
        String rows = decorate("rows", true);
        assertEquals(rows, decorate("reverse", true));
        assertEquals(rows, decorate("random", true));
    }

    @Test
    public void orderIndependentDecorationStillPlacesFeatures() {
        String s = decorate("rows", true);
        assertNotEquals(-1, s.indexOf('f')); // leaves present
        assertNotEquals(-1, s.indexOf('g')); // logs present
    }

    // ---- Deferred operations (loot / block-entity state / entities) follow their block write ----

    /** Target of the overlapping chest writes: chunk (0,0), next to chunk (1,0). */
    static final int PX = 15, PY = 64, PZ = 5;

    /**
     * Chunks (0,0) and (1,0) each place a chest at the same position (inside chunk (0,0)) and defer an
     * operation that records their name; chunk (1,0) also defers an entity-like operation into the air
     * at (PX, PY + 1, PZ), where chunk (0,0) places a block.
     */
    static GenerationStage chestStage(List<String> ran) {
        return world -> {
            int cx = world.centerChunkX(), cz = world.centerChunkZ();
            if(cz != 0 || (cx != 0 && cx != 1)) return;
            String who = cx == 0 ? "A" : "B";
            world.setBlockState(PX, PY, PZ, CHEST);
            ((com.dfsek.terra.api.world.chunk.generation.DeferringWorld) world).defer(PX, PY, PZ, live -> ran.add("loot " + who));
            if(cx == 1) {
                ((com.dfsek.terra.api.world.chunk.generation.DeferringWorld) world).defer(PX, PY + 1, PZ, live -> ran.add("entity B"));
            } else {
                world.setBlockState(PX, PY + 1, PZ, LEAF);
            }
        };
    }

    static List<String> decorateChests(String order, boolean vanillaAtTarget) {
        List<String> ran = new ArrayList<>();
        TestWorld w = new TestWorld();
        if(vanillaAtTarget) w.blocks.put(TestWorld.key(PX, PY, PZ), VANILLA);
        OrderIndependentDecorator dec = null;
        for(int[] c : order(order)) {
            ProtoWorld region = w.region(c[0], c[1]);
            if(dec == null) dec = new OrderIndependentDecorator(region.getPack(), List.of(chestStage(ran)), FLAT, AIR, region);
            dec.decorate(region);
        }
        return ran;
    }

    @Test
    public void onlyTheWinningWritesDeferredOperationRuns() {
        // Chunk (0,0) owns the target, so its write wins; B's loot and B's entity (displaced by A's leaf) don't run.
        assertEquals(List.of("loot A"), decorateChests("rows", false));
        assertEquals(List.of("loot A"), decorateChests("reverse", false));
    }

    @Test
    public void noDeferredOperationRunsWhenTheServerChangedTheTarget() {
        assertEquals(List.of(), decorateChests("rows", true));
    }

    @Test
    public void deferralsThroughAnOffsetWorldTargetTheAbsolutePosition() {
        // Nested TerraScript structures run in an offset (BufferedWorld) view; their deferred work must be
        // tied to the absolute block and see the live world through the same offset.
        List<String> ran = new ArrayList<>();
        GenerationStage nested = world -> {
            if(world.centerChunkX() != 0 || world.centerChunkZ() != 0) return;
            com.dfsek.terra.api.world.WritableWorld inner = world.buffer(10, 0, 0);
            inner.setBlockState(PX - 10, PY, PZ, CHEST);
            ((com.dfsek.terra.api.world.chunk.generation.DeferringWorld) inner).defer(PX - 10, PY, PZ,
                live -> ran.add("inner sees " + live.getBlockState(PX - 10, PY, PZ).getAsString(true)));
        };
        TestWorld w = new TestWorld();
        OrderIndependentDecorator dec = null;
        for(int[] c : order("rows")) {
            ProtoWorld region = w.region(c[0], c[1]);
            if(dec == null) dec = new OrderIndependentDecorator(region.getPack(), List.of(nested), FLAT, AIR, region);
            dec.decorate(region);
        }
        assertEquals(List.of("inner sees chest"), ran);
    }
}
