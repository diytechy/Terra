package com.dfsek.terra.bukkit.nms;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;

import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.bukkit.world.BukkitPlatformBiome;
import com.dfsek.terra.api.Platform;


public class NMSBiomeProvider extends BiomeSource {
    private final BiomeProvider delegate;
    private final long seed;
    private final Registry<Biome> biomeRegistry = RegistryFetcher.biomeRegistry();
    private static final AtomicLong biomeQueryCount = new AtomicLong(0);

    public NMSBiomeProvider(BiomeProvider delegate, long seed) {
        super();
        this.delegate = delegate;
        this.seed = seed;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return delegate.stream()
            .map(biome -> RegistryFetcher.biomeRegistry()
                .getOrThrow(((BukkitPlatformBiome) biome.getPlatformBiome()).getContext()
                    .get(NMSBiomeInfo.class)
                    .biomeKey()));
    }

    @Override
    protected @NotNull MapCodec<? extends BiomeSource> codec() {
        return MapCodec.assumeMapUnsafe(BiomeSource.CODEC);
        //        return MapCodec.unit(null);
        //        BuiltInRegistries.BIOME_SOURCE.byNameCodec().dispatchMap(this::codec, Function.identity());
        //        BuiltInRegistries.BIOME_SOURCE.byNameCodec().dispatchStable(BiomeSource::codec, Function.identity());
        //        return BiomeSource.CODEC;
    }

    @Override
    public @NotNull Holder<Biome> getNoiseBiome(int x, int y, int z, @NotNull Sampler sampler) {
        biomeQueryCount.incrementAndGet();
        return biomeRegistry.getOrThrow(((BukkitPlatformBiome) delegate.getBiome(x << 2, y << 2, z << 2, seed)
            .getPlatformBiome()).getContext()
            .get(NMSBiomeInfo.class)
            .biomeKey());
    }

    /**
     * Get the total number of biome queries since the profiler started.
     */
    public static long getBiomeQueryCount() {
        return biomeQueryCount.get();
    }

    /**
     * Reset the biome query counter.
     */
    public static void resetBiomeQueryCount() {
        biomeQueryCount.set(0);
    }
}
