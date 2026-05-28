/*
 * This file is part of Terra.
 *
 * Terra is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Terra is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Terra.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.dfsek.terra.mod.generation;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.MultiNoiseSampler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.mod.config.ProtoPlatformBiome;
import com.dfsek.terra.mod.data.Codecs;
import com.dfsek.terra.mod.util.SeedHack;


public class TerraBiomeSource extends BiomeSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraBiomeSource.class);
    private ConfigPack pack;

    // Set to true on the calling thread while inside findBiomeHorizontally so that
    // getBiome can take the fast path for structure placement searches.
    // findBiomeHorizontally is the sole caller of getBiome for stronghold ring
    // position searches, and is not called during normal chunk generation.
    private static final ThreadLocal<Boolean> IN_STRUCTURE_SEARCH =
        ThreadLocal.withInitial(() -> false);

    public TerraBiomeSource(ConfigPack pack) {
        this.pack = pack;

        LOGGER.debug("Biomes: " + getBiomes());
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return Codecs.TERRA_BIOME_SOURCE;
    }

    @Override
    protected Stream<Holder<Biome>> biomeStream() {
        return StreamSupport
            .stream(pack.getBiomeProvider()
                .getBiomes()
                .spliterator(), false)
            .map(b -> ((ProtoPlatformBiome) b.getPlatformBiome()).getDelegate());
    }

    // NOTE: The Fabric/Yarn method name for the horizontal biome search used during
    // structure placement may be `findBiomeHorizontally` or `findClosestBiome` depending
    // on the Minecraft version and mapping set. Verify against the active Minecraft jar's
    // BiomeSource class before enabling. The pattern is identical to NMSBiomeProvider.
    @Override
    public @Nullable BlockPos findBiomeHorizontally(int x, int y, int z, int radius, int step,
            Predicate<Holder<Biome>> predicate, RandomSource random,
            boolean bl, Sampler noiseSampler) {
        IN_STRUCTURE_SEARCH.set(true);
        try {
            return super.findBiomeHorizontally(x, y, z, radius, step, predicate, random, bl, noiseSampler);
        } finally {
            IN_STRUCTURE_SEARCH.set(false);
        }
    }

    @Override
    public Holder<Biome> getBiome(int biomeX, int biomeY, int biomeZ, Sampler noiseSampler) {
        long seed = SeedHack.getSeed(noiseSampler);

        if(IN_STRUCTURE_SEARCH.get()) {
            Optional<com.dfsek.terra.api.world.biome.Biome> fast =
                pack.getBiomeProvider().getStructurePlacementBiome(biomeX << 2, biomeZ << 2, seed);
            if(fast.isPresent()) {
                return ((ProtoPlatformBiome) fast.get().getPlatformBiome()).getDelegate();
            }
        }

        return ((ProtoPlatformBiome) pack
            .getBiomeProvider()
            .getBiome(biomeX << 2, biomeY << 2, biomeZ << 2, seed)
            .getPlatformBiome()).getDelegate();
    }

    public BiomeProvider getProvider() {
        return pack.getBiomeProvider();
    }

    public ConfigPack getPack() {
        return pack;
    }

    public void setPack(ConfigPack pack) {
        this.pack = pack;
    }
}
