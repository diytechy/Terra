package com.dfsek.terra.addons.chunkgenerator.generation.math;

import com.dfsek.terra.addons.chunkgenerator.generation.math.samplers.Sampler3D;


public enum SlantCalculationMethod {
    DotProduct {
        @Override
        public double slant(Sampler3D sampler, double x, double y, double z) {
            // Approximate surface normal from 6 axis-aligned samples, then return
            // the Y component of the normalized result (dot with up-vector (0,1,0)).
            // Inlined to eliminate Vector3 allocations on every call.
            double d = DERIVATIVE_DIST;
            double nx = sampler.sample(x - d, y, z) - sampler.sample(x + d, y, z);
            double ny = sampler.sample(x, y - d, z) - sampler.sample(x, y + d, z);
            double nz = sampler.sample(x, y, z - d) - sampler.sample(x, y, z + d);
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            return len == 0.0 ? 0.0 : ny / len;
        }

        @Override
        public boolean floorToThreshold() {
            return false;
        }
    },

    Derivative {
        @Override
        public double slant(Sampler3D sampler, double x, double y, double z) {
            double baseSample = sampler.sample(x, y, z);

            double xVal1 = (sampler.sample(x + DERIVATIVE_DIST, y, z) - baseSample) / DERIVATIVE_DIST;
            double xVal2 = (sampler.sample(x - DERIVATIVE_DIST, y, z) - baseSample) / DERIVATIVE_DIST;
            double zVal1 = (sampler.sample(x, y, z + DERIVATIVE_DIST) - baseSample) / DERIVATIVE_DIST;
            double zVal2 = (sampler.sample(x, y, z - DERIVATIVE_DIST) - baseSample) / DERIVATIVE_DIST;
            double yVal1 = (sampler.sample(x, y + DERIVATIVE_DIST, z) - baseSample) / DERIVATIVE_DIST;
            double yVal2 = (sampler.sample(x, y - DERIVATIVE_DIST, z) - baseSample) / DERIVATIVE_DIST;

            return Math.sqrt(
                ((xVal2 - xVal1) * (xVal2 - xVal1)) + ((zVal2 - zVal1) * (zVal2 - zVal1)) + ((yVal2 - yVal1) * (yVal2 - yVal1)));
        }

        @Override
        public boolean floorToThreshold() {
            return true;
        }
    };

    private static final double DERIVATIVE_DIST = 0.55;

    public abstract double slant(Sampler3D sampler, double x, double y, double z);

    /*
     * Controls whether palettes should be applied before or after their respective thresholds.
     *
     * If true, slant values will map to the palette of the next floor threshold, otherwise they
     * will map to the ceiling.
     */
    public abstract boolean floorToThreshold();
}
