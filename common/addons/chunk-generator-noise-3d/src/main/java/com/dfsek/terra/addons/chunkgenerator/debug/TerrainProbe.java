/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.chunkgenerator.debug;

import com.dfsek.seismic.type.sampler.Sampler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.dfsek.terra.addons.chunkgenerator.config.NoiseChunkGeneratorPackConfigTemplate;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties;
import com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseSamplers;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.properties.Context;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.util.Column;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


/**
 * Direct, un-interpolated evaluation of the terrain blend at one column: the same neighbour
 * stencil and weights as {@code ChunkInterpolator} (surface-biome neighbours), but evaluated at
 * every block Y instead of the 4-block sparse grid.
 * <p>
 * For every distinct terrain sampler that contributes to the blend it reports that sampler's
 * <em>own</em> surface height at this column (the height it would generate alone), plus the
 * surface height of the weighted blend. This is the data needed to tell the two ravine causes
 * apart:
 * <ul>
 *   <li>cause 1 (fade): every contributor's own height is already low here;</li>
 *   <li>cause 2 (non-height-map density): contributors are high but the blended top is below
 *       all of them.</li>
 * </ul>
 * The harness calls {@link #probe} reflectively (the CLI cannot import addon classes).
 */
public final class TerrainProbe {
    private TerrainProbe() {}

    public static String probe(ConfigPack pack, long seed, int x, int z, int minY, int maxY) {
        PropertyKey<BiomeNoiseProperties> key = Context.create(BiomeNoiseProperties.class);
        NoiseChunkGeneratorPackConfigTemplate config = pack.getContext().get(NoiseChunkGeneratorPackConfigTemplate.class);
        int blendMinY = config.getBlendMinY();
        int blendMaxY = config.getBlendMaxY();
        BiomeProvider provider = pack.getBiomeProvider();

        Column<Biome> column = provider.getColumnForTerrain(x, z, seed, minY, maxY);
        Biome surface = column.getSurface();
        BiomeNoiseSamplers surfaceSamplers = surface.getContext().get(key).samplers();
        int blend = surfaceSamplers.blendDistance();
        int step = surfaceSamplers.blendStep();

        List<BiomeNoiseSamplers> samplers = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        List<List<String>> ids = new ArrayList<>();
        // Neighbour-only map, exactly as ChunkInterpolator builds it (centre excluded).
        List<BiomeNoiseSamplers> nSamplers = new ArrayList<>();
        List<Double> nWeights = new ArrayList<>();
        List<List<String>> nIds = new ArrayList<>();
        // Reporting list: centre first, neighbours sharing a sampler merged into one entry.
        add(samplers, weights, ids, surfaceSamplers, surfaceSamplers.blendWeight(), surface.getID());
        for(int xi = -blend; xi <= blend; xi++) {
            for(int zi = -blend; zi <= blend; zi++) {
                if(xi == 0 && zi == 0) continue;
                Biome b = provider.getColumnForTerrain(x + xi * step, z + zi * step, seed, minY, maxY).getSurface();
                BiomeNoiseSamplers s = b.getContext().get(key).samplers();
                add(samplers, weights, ids, s, s.blendWeight(), b.getID());
                add(nSamplers, nWeights, nIds, s, s.blendWeight(), b.getID());
            }
        }
        double total = 0;
        for(double w : weights) total += w;

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"x\":").append(x).append(",\"z\":").append(z)
            .append(",\"surface\":\"").append(surface.getID()).append('"')
            .append(",\"blendDistance\":").append(blend).append(",\"blendStep\":").append(step)
            .append(",\"contributors\":[");
        for(int i = 0; i < samplers.size(); i++) {
            BiomeNoiseSamplers s = samplers.get(i);
            int own = topOf(s.base(), seed, x, z, minY, maxY);
            if(i > 0) sb.append(',');
            sb.append("{\"biomes\":[");
            for(int j = 0; j < ids.get(i).size(); j++) {
                if(j > 0) sb.append(',');
                sb.append('"').append(ids.get(i).get(j)).append('"');
            }
            sb.append("],\"weight\":").append(fmt(weights.get(i) / total))
                .append(",\"ownTop\":").append(own)
                .append(",\"heightMap\":").append(isHeightMap(s.base(), seed, x, z, own, minY, maxY))
                .append('}');
        }
        sb.append(']');

        // Blended surface (exact per-block Y, no sparse interpolation). Outside the blend Y range,
        // or when the centre has blend distance 0, ChunkInterpolator uses the centre sampler alone.
        int blendedTop = minY - 1;
        for(int y = maxY - 1; y >= minY; y--) {
            BiomeNoiseSamplers centre = column.get(y).getContext().get(key).samplers();
            double d;
            if(centre.blendDistance() == 0 || y < blendMinY || y > blendMaxY) {
                d = centre.base().getSample(seed, x, y, z);
            } else {
                // Centre contributes its per-Y (extruded) sampler with its own weight; neighbours
                // contribute their surface samplers, as in ChunkInterpolator (extruded-neighbors: false).
                double cw = centre.blendWeight();
                double sum = centre.base().getSample(seed, x, y, z) * cw;
                double wsum = cw;
                for(int i = 0; i < nSamplers.size(); i++) {
                    sum += nSamplers.get(i).base().getSample(seed, x, y, z) * nWeights.get(i);
                    wsum += nWeights.get(i);
                }
                d = sum / wsum;
            }
            if(d > 0) {
                blendedTop = y;
                break;
            }
        }
        sb.append(",\"blendedTop\":").append(blendedTop);
        double carve = surfaceSamplers.carving().getSample(seed, x, blendedTop, z);
        sb.append(",\"carvingAtTop\":").append(fmt(carve));
        sb.append('}');
        return sb.toString();
    }

    private static void add(List<BiomeNoiseSamplers> samplers, List<Double> weights, List<List<String>> ids,
                            BiomeNoiseSamplers s, double w, String id) {
        for(int i = 0; i < samplers.size(); i++) {
            if(samplers.get(i) == s) {
                weights.set(i, weights.get(i) + w);
                if(!ids.get(i).contains(id)) ids.get(i).add(id);
                return;
            }
        }
        samplers.add(s);
        weights.add(w);
        List<String> l = new ArrayList<>();
        l.add(id);
        ids.add(l);
    }

    /** Highest Y whose density is positive: coarse 4-block scan from the top, then refined. */
    static int topOf(Sampler s, long seed, int x, int z, int minY, int maxY) {
        for(int y = maxY - 1; y >= minY; y -= 4) {
            if(s.getSample(seed, x, y, z) > 0) {
                int top = y;
                for(int yy = Math.min(maxY - 1, y + 3); yy > y; yy--) {
                    if(s.getSample(seed, x, yy, z) > 0) {
                        top = yy;
                        break;
                    }
                }
                return top;
            }
        }
        return minY - 1;
    }

    /**
     * True when the sampler behaves like {@code -y + h(x,z)} in this column: its density falls by
     * exactly 1 per block around the top and is solid everywhere in the 24 blocks below it.
     */
    private static boolean isHeightMap(Sampler s, long seed, int x, int z, int top, int minY, int maxY) {
        if(top < minY || top >= maxY - 1) return false;
        double a = s.getSample(seed, x, top, z);
        double b = s.getSample(seed, x, top + 1, z);
        if(Math.abs((a - b) - 1.0) > 1e-6) return false;
        for(int y = top - 1; y >= Math.max(minY, top - 24); y--) {
            if(s.getSample(seed, x, y, z) <= 0) return false;
        }
        return true;
    }

    private static String fmt(double d) {
        if(Double.isNaN(d) || Double.isInfinite(d)) return "null";
        return String.format(Locale.ROOT, "%.4f", d);
    }
}
