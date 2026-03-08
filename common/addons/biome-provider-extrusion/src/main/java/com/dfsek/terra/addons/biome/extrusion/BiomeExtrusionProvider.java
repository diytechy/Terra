package com.dfsek.terra.addons.biome.extrusion;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.dfsek.terra.addons.biome.extrusion.api.Extrusion;
import com.dfsek.terra.addons.biome.extrusion.extrusions.ReplaceExtrusion;
import com.dfsek.terra.addons.biome.extrusion.utils.ExtrusionPipeline;
import com.dfsek.terra.addons.biome.extrusion.utils.ExtrusionPipelineFactory;
import com.dfsek.terra.addons.biome.query.BiomeQueryAPIAddon;
import com.dfsek.terra.addons.biome.query.impl.BiomeTagFlattener;
import com.dfsek.terra.api.util.Column;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


public class BiomeExtrusionProvider implements BiomeProvider {
    public final ExtrusionPipeline pipeline;
    private final BiomeProvider delegate;
    private final Set<Biome> biomes;
    private final List<Extrusion> extrusions;
    private final int resolution;
    private final int yResolution;

    public BiomeExtrusionProvider(BiomeProvider delegate, List<Extrusion> extrusions, int resolution, int yResolution) {
        this.delegate = delegate;
        this.extrusions = extrusions;
        this.biomes = delegate.stream().collect(Collectors.toSet());
        extrusions.forEach(e -> biomes.addAll(e.getBiomes()));

        validateExtrusionTags(extrusions);

        this.pipeline = ExtrusionPipelineFactory.create(extrusions);

        this.resolution = resolution;
        this.yResolution = yResolution;
    }

    private void validateExtrusionTags(List<Extrusion> extrusions) {
        BiomeTagFlattener flattener = biomes.stream()
            .findFirst()
            .map(biome -> biome.getContext().get(BiomeQueryAPIAddon.BIOME_TAG_KEY).getFlattener())
            .orElse(null);

        if(flattener == null) return;

        for(Extrusion extrusion : extrusions) {
            if(extrusion instanceof ReplaceExtrusion replaceExtrusion) {
                String tag = replaceExtrusion.getTag();
                if(!flattener.contains(tag)) {
                    throw new IllegalArgumentException(
                        "Extrusion references unknown biome tag '" + tag + "' in 'from' field. " +
                        "No biome in this pack defines this tag. " +
                        "Check your biome-provider extrusion config for 'from: " + tag + "'.");
                }
            }
        }
    }

    public List<Extrusion> getExtrusions() {
        return extrusions;
    }

    @Override
    public Biome getBiome(int x, int y, int z, long seed) {
        Biome delegated = delegate.getBiome(x, y, z, seed);
        return pipeline.extrude(delegated, x, y, z, seed);
    }

    @Override
    public Column<Biome> getColumn(int x, int z, long seed, int min, int max) {
        return delegate.getBaseBiome(x, z, seed)
            .map(base -> (Column<Biome>) new BaseBiomeColumn(this, base, min, max, x, z, seed))
            .orElseGet(() -> BiomeProvider.super.getColumn(x, z, seed, min, max));
    }

    @Override
    public Optional<Biome> getBaseBiome(int x, int z, long seed) {
        return delegate.getBaseBiome(x, z, seed);
    }

    @Override
    public Iterable<Biome> getBiomes() {
        return biomes;
    }

    @Override
    public int resolution() {
        return resolution;
    }

    @Override
    public int yResolution() {
        return yResolution;
    }

    public BiomeProvider getDelegate() {
        return delegate;
    }
}