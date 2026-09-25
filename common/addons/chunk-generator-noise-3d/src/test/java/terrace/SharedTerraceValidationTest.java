package terrace;

import com.dfsek.seismic.type.sampler.Sampler;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import com.dfsek.terra.addons.chunkgenerator.config.noise.terrace.SharedTerrace;
import com.dfsek.terra.addons.chunkgenerator.config.noise.terrace.TerraceSpec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** terrain.terrace validation is per (spec, base sampler, world seed) and not held statically. */
public class SharedTerraceValidationTest {
    static Sampler constant(double v) {
        return new Sampler() {
            @Override public double getSample(long seed, double x, double y) { return v; }
            @Override public double getSample(long seed, double x, double y, double z) { return v; }
        };
    }

    /** Height map whose surface is at 70 for seed 1 and at 20 for any other seed. */
    static final Sampler SEED_DEPENDENT_BASE = new Sampler() {
        @Override public double getSample(long seed, double x, double y) { return 0; }
        @Override public double getSample(long seed, double x, double y, double z) { return (seed == 1 ? 70 : 20) - y; }
    };

    /** Flat surface at 70 for every seed (no passes). */
    static TerraceSpec spec() {
        return new TerraceSpec(constant(0), constant(70), constant(1), new TerraceSpec.Pass[0]);
    }

    @Test
    public void validationIsPerSeed() {
        TerraceSpec s = spec();
        assertNotNull(SharedTerrace.usable(s, SEED_DEPENDENT_BASE, 1));
        assertNull(SharedTerrace.usable(s, SEED_DEPENDENT_BASE, 2)); // was accepted from seed 1's cached result
        TerraceSpec t = spec();
        assertNull(SharedTerrace.usable(t, SEED_DEPENDENT_BASE, 2));
        assertNotNull(SharedTerrace.usable(t, SEED_DEPENDENT_BASE, 1));
    }

    @Test
    public void validatedSpecsAreNotRetained() throws InterruptedException {
        TerraceSpec s = spec();
        SharedTerrace.usable(s, SEED_DEPENDENT_BASE, 1);
        WeakReference<TerraceSpec> ref = new WeakReference<>(s);
        s = null;
        for(int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue(ref.get() == null, "validated spec is still strongly reachable");
    }

    @Test
    public void validationResultsPerSpecAreBoundedAndEvictedResultsRecomputeTheSame() {
        TerraceSpec s = spec();
        for(long seed = 1; seed <= 1_000; seed++) SharedTerrace.usable(s, SEED_DEPENDENT_BASE, seed);
        assertTrue(s.validation().size() <= TerraceSpec.Validation.MAX_ENTRIES, "held " + s.validation().size());
        assertNotNull(SharedTerrace.usable(s, SEED_DEPENDENT_BASE, 1)); // seed 1 was evicted long ago
        assertNull(SharedTerrace.usable(s, SEED_DEPENDENT_BASE, 2));
    }

    @Test
    public void extrudedNeighboursWithTerraceSpecsAreRejected() {
        assertNull(SharedTerrace.unsupportedCombination(false, java.util.List.of("A")));
        assertNull(SharedTerrace.unsupportedCombination(true, java.util.List.of()));
        String e = SharedTerrace.unsupportedCombination(true, java.util.List.of("MESA", "TERRACES"));
        assertNotNull(e);
        assertTrue(e.contains("extruded-neighbors") && e.contains("MESA") && e.contains("TERRACES"), e);
    }
}
