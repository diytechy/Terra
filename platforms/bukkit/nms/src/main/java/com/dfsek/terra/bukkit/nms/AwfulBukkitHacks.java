package com.dfsek.terra.bukkit.nms;

import net.minecraft.core.Holder;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.biome.Biome;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.dfsek.terra.bukkit.nms.config.VanillaBiomeProperties;
import com.dfsek.terra.bukkit.world.BukkitBiomeInfo;
import com.dfsek.terra.bukkit.world.BukkitPlatformBiome;
import com.dfsek.terra.registry.master.ConfigRegistry;


/**
 * Injects Terra's custom biomes into the (already-frozen) vanilla biome registry on Bukkit/Paper,
 * reaching into registry internals via {@link Reflection} because Paper's registry-modification API
 * does not yet expose the biome registry.
 *
 * <p>TODO: replace with {@code RegistryEvents.BIOME.compose()} once Paper exposes the biome registry.
 * Paper already drives this for ~16 registries (game event, enchantment, mob variants, sulfur cube
 * archetype, dialog, ...) through {@code RegistryComposeEvent}, which exposes
 * {@code registry().register(...)} and {@code getOrCreateTag(...)} and lets Paper own the freeze
 * lifecycle. When {@code worldgen/biome} is added there, this class and most of {@link Reflection}
 * can collapse into a small compose listener.
 */
public class AwfulBukkitHacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(AwfulBukkitHacks.class);

    private static final Map<Identifier, List<Identifier>> terraBiomeMap = new HashMap<>();

    public static void registerBiomes(ConfigRegistry configRegistry) {
        try {
            LOGGER.info("Hacking biome registry...");
            MappedRegistry<Biome> biomeRegistry = (MappedRegistry<Biome>) RegistryFetcher.biomeRegistry();

            // Unfreeze the biome registry to allow modification
            Reflection.MAPPED_REGISTRY.setFrozen(biomeRegistry, false);

            // Register the terra biomes to the registry
            configRegistry.forEach(pack -> pack.getRegistry(com.dfsek.terra.api.world.biome.Biome.class).forEach((key, biome) -> {
                try {
                    BukkitPlatformBiome platformBiome = (BukkitPlatformBiome) biome.getPlatformBiome();

                    NamespacedKey vanillaBukkitKey = platformBiome.getHandle().getKey();
                    Identifier vanillaMinecraftKey = Identifier.fromNamespaceAndPath(vanillaBukkitKey.getNamespace(),
                        vanillaBukkitKey.getKey());

                    VanillaBiomeProperties vanillaBiomeProperties = biome.getContext().get(VanillaBiomeProperties.class);

                    Biome platform = NMSBiomeInjector.createBiome(biomeRegistry.get(vanillaMinecraftKey).orElseThrow().value(),
                        vanillaBiomeProperties);

                    Identifier delegateMinecraftKey = Identifier.fromNamespaceAndPath("terra",
                        NMSBiomeInjector.createBiomeID(pack, key));
                    NamespacedKey delegateBukkitKey = NamespacedKey.fromString(delegateMinecraftKey.toString());
                    ResourceKey<Biome> delegateKey = ResourceKey.create(Registries.BIOME, delegateMinecraftKey);

                    Reference<Biome> holder = biomeRegistry.register(delegateKey, platform, RegistrationInfo.BUILT_IN);
                    Reflection.REFERENCE.invokeBindValue(holder, platform); // IMPORTANT: bind holder.

                    platformBiome.getContext().put(new BukkitBiomeInfo(delegateBukkitKey));
                    platformBiome.getContext().put(new NMSBiomeInfo(delegateKey));

                    Map<ResourceKey<Biome>, ResourceKey<VillagerType>> villagerMap = Reflection.VILLAGER_TYPE.getByBiome();

                    villagerMap.put(delegateKey,
                        Objects.requireNonNullElse(vanillaBiomeProperties.getVillagerType(),
                            villagerMap.getOrDefault(delegateKey, VillagerType.PLAINS)));

                    terraBiomeMap.computeIfAbsent(vanillaMinecraftKey, i -> new ArrayList<>()).add(delegateKey.identifier());

                    LOGGER.debug("Registered biome: " + delegateKey);
                } catch(NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }));

            Reflection.MAPPED_REGISTRY.setFrozen(biomeRegistry, true); // freeze registry again :)

            LOGGER.info("Doing tag garbage....");
            Map<TagKey<Biome>, List<Holder<Biome>>> collect = biomeRegistry
                .getTags() // streamKeysAndEntries
                .collect(HashMap::new,
                    (map, pair) ->
                        map.put(pair.key(), new ArrayList<>(Reflection.HOLDER_SET.invokeContents(pair).stream().toList())),
                    HashMap::putAll);

            terraBiomeMap
                .forEach((vb, terraBiomes) ->
                    NMSBiomeInjector.getEntry(biomeRegistry, vb).ifPresentOrElse(
                        vanilla -> terraBiomes.forEach(
                            tb -> NMSBiomeInjector.getEntry(biomeRegistry, tb).ifPresentOrElse(
                                terra -> {
                                    LOGGER.debug("{} (vanilla for {}): {}",
                                        vanilla.unwrapKey().orElseThrow().identifier(),
                                        terra.unwrapKey().orElseThrow().identifier(),
                                        vanilla.tags().toList());
                                    vanilla.tags()
                                        .forEach(tag -> collect
                                            .computeIfAbsent(tag, t -> new ArrayList<>())
                                            .add(terra));
                                },
                                () -> LOGGER.error("No such biome: {}", tb))),
                        () -> LOGGER.error("No vanilla biome: {}", vb)));

            // 26.2 keeps tags in a separate `frozenTags` map and only binds the live `allTags`
            // inside freeze(), which now throws if `allTags` is already bound. The old code bound
            // `allTags` directly, leaving the biome registry in a state the client-connect re-freeze
            // (SynchronizeRegistriesTask) rejects with "Tags already present before freezing".
            // Instead, unbind `allTags`, stage the full tag set through the registry's own
            // bindTags(...), and let freeze() promote it back -- mirroring vanilla's freeze lifecycle.
            Reflection.MAPPED_REGISTRY.setFrozen(biomeRegistry, false);
            Reflection.MAPPED_REGISTRY.setAllTags(biomeRegistry, Reflection.MAPPED_REGISTRY_TAG_SET.invokeUnbound());
            biomeRegistry.bindTags(collect);
            biomeRegistry.freeze();

        } catch(SecurityException | IllegalArgumentException exception) {
            throw new RuntimeException(exception);
        }
    }

}

