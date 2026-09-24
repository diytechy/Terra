/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.config.noise.terrace;

/**
 * The one terrace function shared by every terraced biome in a blend: blends the contributors'
 * terrace <em>parameters</em> by weight and terraces the blended height once. See {@link TerraceSpec}.
 */
public final class SharedTerrace {
    private SharedTerrace() {}

    /**
     * @param specs   terrace specs of the terraced contributors (non-null entries only are used)
     * @param weights blend weights, same order
     * @param n       number of entries to read
     *
     * @return the terraced surface height (absolute Y), or NaN if no entry has a spec
     */
    public static double surface(TerraceSpec[] specs, double[] weights, int n, long seed, double x, double z) {
        double w = 0, anchor = 0, height = 0, fade = 0;
        int maxPasses = 0;
        for(int b = 0; b < n; b++) {
            TerraceSpec s = specs[b];
            if(s == null) continue;
            double wb = weights[b];
            w += wb;
            anchor += wb * s.anchor().getSample(seed, x, z);
            height += wb * s.height().getSample(seed, x, z);
            fade += wb * s.fade().getSample(seed, x, z);
            maxPasses = Math.max(maxPasses, s.passes().length);
        }
        if(w <= 0) return Double.NaN;
        anchor /= w;
        height /= w;
        fade /= w;

        double i = height;
        for(int k = 0; k < maxPasses; k++) {
            // Step and offset are averaged over the biomes that have this pass; depth over all
            // terraced contributors (a biome without the pass contributes depth 0).
            double wk = 0, step = 0, offset = 0, depth = 0;
            for(int b = 0; b < n; b++) {
                TerraceSpec s = specs[b];
                if(s == null || s.passes().length <= k) continue;
                TerraceSpec.Pass p = s.passes()[k];
                double wb = weights[b];
                wk += wb;
                step += wb * p.step().getSample(seed, x, z);
                offset += wb * p.offset().getSample(seed, x, z);
                depth += wb * p.depth().getSample(seed, x, z);
            }
            if(wk <= 0) continue;
            step /= wk;
            offset /= wk;
            depth /= w;
            if(!(step > 1e-6)) continue;
            double phase = i / step - offset;
            double frac = phase - Math.floor(phase);
            double profile = 0;
            for(int b = 0; b < n; b++) {
                TerraceSpec s = specs[b];
                if(s == null || s.passes().length <= k) continue;
                profile += weights[b] * TerraceSpec.profile(s.passes()[k].profile(), frac);
            }
            profile /= wk;
            i = i + depth * step * profile;
        }
        return anchor + fade * i;
    }
}
