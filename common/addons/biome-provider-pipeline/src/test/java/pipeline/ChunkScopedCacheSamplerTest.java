package pipeline;

import com.dfsek.seismic.type.sampler.Sampler;
import org.junit.jupiter.api.Test;

import com.dfsek.terra.addons.biome.pipeline.cache.ChunkGenerationContext;
import com.dfsek.terra.addons.biome.pipeline.cache.ChunkScopedCacheSampler;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * The chunk-scoped cache must be pure memoization: whatever was evaluated before, a sample must
 * equal the wrapped sampler's value at that exact point and seed. The old lookup truncated
 * coordinates and divided toward zero, so these orders returned a neighbour's value.
 */
public class ChunkScopedCacheSamplerTest {
    private static final Sampler FIELD = new Sampler() {
        @Override
        public double getSample(long seed, double x, double z) {
            return seed * 1_000_000.0 + x * 1000.0 + z;
        }

        @Override
        public double getSample(long seed, double x, double y, double z) {
            return getSample(seed, x, z);
        }
    };

    private static ChunkScopedCacheSampler cached(ChunkGenerationContext ctx) {
        ThreadLocal<ChunkGenerationContext> tl = ThreadLocal.withInitial(() -> ctx);
        return new ChunkScopedCacheSampler(FIELD, tl, 0);
    }

    private static ChunkGenerationContext context(long seed) {
        ChunkGenerationContext ctx = new ChunkGenerationContext(1, 8);
        ctx.reset(0, 0, 4, seed); // grid points 0, 4, 8, ... 28 on each axis
        return ctx;
    }

    @Test
    public void offGridPointDoesNotPoisonGridCell() {
        ChunkScopedCacheSampler s = cached(context(1));
        assertEquals(FIELD.getSample(1, 5.5, 3.2), s.getSample(1, 5.5, 3.2));
        assertEquals(FIELD.getSample(1, 4, 0), s.getSample(1, 4, 0)); // old: returned f(5.5, 3.2)
    }

    @Test
    public void gridCellDoesNotAnswerOffGridPoint() {
        ChunkScopedCacheSampler s = cached(context(1));
        assertEquals(FIELD.getSample(1, 4, 0), s.getSample(1, 4, 0));
        assertEquals(FIELD.getSample(1, 6, 1), s.getSample(1, 6, 1)); // old: returned f(4, 0)
    }

    @Test
    public void pointJustBeforeOriginIsNotCell0() {
        ChunkScopedCacheSampler s = cached(context(1));
        assertEquals(FIELD.getSample(1, -3, -1), s.getSample(1, -3, -1));
        assertEquals(FIELD.getSample(1, 0, 0), s.getSample(1, 0, 0)); // old: -3/4 == 0 -> cell 0
    }

    @Test
    public void otherSeedIsNotServedFromCache() {
        ChunkScopedCacheSampler s = cached(context(1));
        assertEquals(FIELD.getSample(1, 8, 8), s.getSample(1, 8, 8));
        assertEquals(FIELD.getSample(2, 8, 8), s.getSample(2, 8, 8)); // old: returned seed 1's value
    }

    @Test
    public void gridPointsAreStillCached() {
        int[] calls = { 0 };
        Sampler counting = new Sampler() {
            @Override
            public double getSample(long seed, double x, double z) {
                calls[0]++;
                return FIELD.getSample(seed, x, z);
            }

            @Override
            public double getSample(long seed, double x, double y, double z) {
                return getSample(seed, x, z);
            }
        };
        ChunkGenerationContext ctx = context(1);
        ChunkScopedCacheSampler s = new ChunkScopedCacheSampler(counting, ThreadLocal.withInitial(() -> ctx), 0);
        s.getSample(1, 12, 20);
        s.getSample(1, 12, 20);
        assertEquals(1, calls[0]);
    }

    @Test
    public void invalidatedContextDelegates() {
        ChunkGenerationContext ctx = context(1);
        ChunkScopedCacheSampler s = cached(ctx);
        s.getSample(1, 4, 4);
        ctx.invalidate();
        assertEquals(FIELD.getSample(1, 4, 4), s.getSample(1, 4, 4));
    }
}
