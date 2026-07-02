package com.dfsek.terra.bukkit.nms;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Predicate;
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

    // Set to true on the calling thread while inside findBiomeHorizontal so that getNoiseBiome
    // can take the structure-search fast path. findBiomeHorizontal is the caller used for
    // stronghold ring position searches (~415,872 biome queries at world creation) and is not
    // used during normal chunk generation, so the fast path never affects terrain.
    private static final ThreadLocal<Boolean> IN_STRUCTURE_SEARCH = ThreadLocal.withInitial(() -> false);

    public NMSBiomeProvider(BiomeProvider delegate, long seed) {
        super();
        this.delegate = delegate;
        this.seed = seed;
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int step,
            Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Sampler noiseSampler) {
        IN_STRUCTURE_SEARCH.set(true);
        try {
            return super.findBiomeHorizontal(x, y, z, radius, step, predicate, random, bl, noiseSampler);
        } finally {
            IN_STRUCTURE_SEARCH.set(false);
        }
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
        if(IN_STRUCTURE_SEARCH.get()) {
            Optional<com.dfsek.terra.api.world.biome.Biome> fast =
                delegate.getStructurePlacementBiome(x << 2, z << 2, seed);
            if(fast.isPresent()) {
                return biomeRegistry.getOrThrow(((BukkitPlatformBiome) fast.get().getPlatformBiome()).getContext()
                    .get(NMSBiomeInfo.class)
                    .biomeKey());
            }
        }
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
