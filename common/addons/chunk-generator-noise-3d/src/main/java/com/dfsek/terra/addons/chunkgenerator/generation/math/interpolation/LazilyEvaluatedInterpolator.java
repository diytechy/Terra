package com.dfsek.terra.addons.chunkgenerator.generation.math.interpolation;

import com.dfsek.seismic.math.floatingpoint.FloatingPointFunctions;
import com.dfsek.seismic.math.numericanalysis.interpolation.InterpolationFunctions;
import com.dfsek.seismic.type.sampler.Sampler;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class LazilyEvaluatedInterpolator {
    private final float[] samples;
    private final Map<Biome, Sampler> carvingSamplerCache = new IdentityHashMap<>();

    private final int chunkX;
    private final int chunkZ;

    private final int horizontalRes;
    private final int verticalRes;

    private final BiomeProvider biomeProvider;
    private final PropertyKey<BiomeNoiseProperties> noisePropertiesKey;

    private final long seed;
    private final int min, max;

    private final int zMul, yMul;

    public LazilyEvaluatedInterpolator(BiomeProvider biomeProvider, int cx, int cz, int max,
                                       PropertyKey<BiomeNoiseProperties> noisePropertiesKey, int min, int horizontalRes, int verticalRes,
                                       long seed) {
        this.noisePropertiesKey = noisePropertiesKey;
        int hSamples = FloatingPointFunctions.ceil(16.0 / horizontalRes);
        int vSamples = FloatingPointFunctions.ceil((double) (max - min) / verticalRes);
        this.zMul = (hSamples + 1);
        this.yMul = zMul * zMul;
        samples = new float[yMul * (vSamples + 1)];
        Arrays.fill(samples, Float.NaN);
        this.chunkX = cx << 4;
        this.chunkZ = cz << 4;
        this.horizontalRes = horizontalRes;
        this.verticalRes = verticalRes;
        this.biomeProvider = biomeProvider;
        this.seed = seed;
        this.min = min;
        this.max = max - 1;
    }

    // Pins each cache cell to its canonical grid point so the cached value is independent
    // of which caller populated it first. Without this, a top-down column scan caches every
    // cell at its top y, and the interpolator (which treats samples as if they sit at cell
    // bottoms) shifts y-dependent carving down by verticalRes-1 blocks.
    private double cachedSample(int xIndex, int yIndex, int zIndex) {
        int index = xIndex + (zIndex * zMul) + (yIndex * yMul);
        float sample = samples[index];
        if(Float.isNaN(sample)) {
            int xi = xIndex * horizontalRes + chunkX;
            int zi = zIndex * horizontalRes + chunkZ;
            int y = Math.min(max, yIndex * verticalRes + min);

            Biome biome = biomeProvider.getBiome(xi, y, zi, seed);
            Sampler carver = carvingSamplerCache.computeIfAbsent(
                biome, b -> b.getContext().get(noisePropertiesKey).samplers().carving());

            sample = (float) carver.getSample(seed, xi, y, zi);
            samples[index] = sample;
        }
        return sample;
    }

    public double sample(int x, int y, int z) {
        int xIndex = x / horizontalRes;
        int yIndex = (y - min) / verticalRes;
        int zIndex = z / horizontalRes;

        double sample_0_0_0 = cachedSample(xIndex, yIndex, zIndex);

        boolean yRange = y % verticalRes == 0;
        if(x % horizontalRes == 0 && yRange && z % horizontalRes == 0) { // we're at the sampling point
            return sample_0_0_0;
        }

        double sample_0_0_1 = cachedSample(xIndex, yIndex, zIndex + 1);

        double sample_1_0_0 = cachedSample(xIndex + 1, yIndex, zIndex);
        double sample_1_0_1 = cachedSample(xIndex + 1, yIndex, zIndex + 1);

        double xFrac = (double) (x % horizontalRes) / horizontalRes;
        double zFrac = (double) (z % horizontalRes) / horizontalRes;
        double lerp_bottom_0 = InterpolationFunctions.lerp(sample_0_0_0, sample_0_0_1, zFrac);
        double lerp_bottom_1 = InterpolationFunctions.lerp(sample_1_0_0, sample_1_0_1, zFrac);

        double lerp_bottom = InterpolationFunctions.lerp(lerp_bottom_0, lerp_bottom_1, xFrac);

        if(yRange) { // we can do bilerp
            return lerp_bottom;
        }

        double yFrac = (double) Math.floorMod(y, verticalRes) / verticalRes;


        double sample_0_1_0 = cachedSample(xIndex, yIndex + 1, zIndex);
        double sample_0_1_1 = cachedSample(xIndex, yIndex + 1, zIndex + 1);


        double sample_1_1_0 = cachedSample(xIndex + 1, yIndex + 1, zIndex);
        double sample_1_1_1 = cachedSample(xIndex + 1, yIndex + 1, zIndex + 1);

        double lerp_top_0 = InterpolationFunctions.lerp(sample_0_1_0, sample_0_1_1, zFrac);
        double lerp_top_1 = InterpolationFunctions.lerp(sample_1_1_0, sample_1_1_1, zFrac);

        double lerp_top = InterpolationFunctions.lerp(lerp_top_0, lerp_top_1, xFrac);

        return InterpolationFunctions.lerp(lerp_bottom, lerp_top, yFrac);
    }
}
