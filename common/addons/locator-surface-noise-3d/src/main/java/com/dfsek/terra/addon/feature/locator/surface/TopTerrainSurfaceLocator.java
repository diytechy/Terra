package com.dfsek.terra.addon.feature.locator.surface;

import com.dfsek.terra.addons.chunkgenerator.generation.NoiseChunkGenerator3D;
import com.dfsek.terra.addons.chunkgenerator.generation.math.samplers.Sampler3D;
import com.dfsek.terra.api.structure.feature.BinaryColumn;
import com.dfsek.terra.api.structure.feature.Locator;
import com.dfsek.terra.api.world.World;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.util.Column;


public class TopTerrainSurfaceLocator implements Locator {

    private final int offset;

    public TopTerrainSurfaceLocator(int offset) {
        this.offset = offset;
    }

    @Override
    public BinaryColumn getSuitableCoordinates(Column<?> column) {
        int x = column.getX();
        int z = column.getZ();
        World world = column.getWorld();
        NoiseChunkGenerator3D generator = (NoiseChunkGenerator3D) world.getGenerator();
        BiomeProvider biomeProvider = world.getBiomeProvider();

        Sampler3D sampler = generator.samplerProvider().get(x, z, world, biomeProvider);

        // Sampler3D.sample(int, int, int) expects chunk-relative x/z (0-15)
        int fdX = Math.floorMod(x, 16);
        int fdZ = Math.floorMod(z, 16);

        int minY = column.getMinY();
        int maxY = column.getMaxY();

        // Scan downward so the first hit is the highest surface
        for(int y = maxY - 1; y >= minY; y--) {
            if(sampler.sample(fdX, y, fdZ) > 0) {
                // Treat the world ceiling as air so y == maxY-1 is handled safely
                boolean airAbove = (y + 1 >= maxY) || sampler.sample(fdX, y + 1, fdZ) <= 0;
                if(airAbove) {
                    int target = y + offset;
                    if(target >= minY && target < maxY) {
                        return new BinaryColumn(target, target + 1, yi -> true);
                    }
                    // Offset pushes result out of column bounds — no valid placement
                    return BinaryColumn.getNull();
                }
            }
        }

        return BinaryColumn.getNull();
    }
}
