package com.dfsek.terra.minestom.biome;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.attribute.AmbientSounds;
import net.minestom.server.world.attribute.EnvironmentAttribute;
import net.minestom.server.world.biome.Biome;
import net.minestom.server.world.biome.BiomeEffects;
import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.minestom.api.BiomeFactory;
import com.dfsek.terra.minestom.config.VanillaBiomeProperties;


public class MinestomUserDefinedBiomeFactory implements BiomeFactory {
    // EnvironmentAttributes (the constants holder) is package-private in Minestom 26.1.1,
    // so we look attributes up by their key strings via the public values() iterator.
    // Revisit once Minestom exposes the constants directly.
    private static final Map<String, EnvironmentAttribute<?>> ATTR_BY_KEY;
    static {
        Map<String, EnvironmentAttribute<?>> map = new HashMap<>();
        for(EnvironmentAttribute<?> attr : EnvironmentAttribute.values()) {
            map.put(attr.key().asString(), attr);
        }
        ATTR_BY_KEY = Map.copyOf(map);
    }

    @SuppressWarnings("unchecked")
    private static <T> EnvironmentAttribute<T> attr(String namespacedKey) {
        EnvironmentAttribute<?> a = ATTR_BY_KEY.get(namespacedKey);
        if(a == null) throw new IllegalStateException("Minestom environment attribute not found: " + namespacedKey);
        return (EnvironmentAttribute<T>) a;
    }

    private final DynamicRegistry<Biome> biomeRegistry = MinecraftServer.getBiomeRegistry();
    private final @NotNull Biome plainsBiome = Objects.requireNonNull(biomeRegistry.get(Key.key("minecraft:plains")));

    private static <T> T mergeNullable(T first, T second) {
        if(first == null) return second;
        return first;
    }

    private static <T> void setIfPresent(Biome.Builder builder, EnvironmentAttribute<T> attribute, T value) {
        if(value != null) builder.setAttribute(attribute, value);
    }

    @Subst("value")
    protected static String createBiomeID(ConfigPack pack, String biomeId) {
        return pack.getID().toLowerCase() + "/" + biomeId.toLowerCase(Locale.ROOT);
    }

    @Override
    public UserDefinedBiome create(ConfigPack pack, com.dfsek.terra.api.world.biome.Biome source) {
        VanillaBiomeProperties properties = source.getContext().get(VanillaBiomeProperties.class);
        RegistryKey<Biome> parentKey = ((MinestomBiome) source.getPlatformBiome()).getHandle();
        Biome parent = mergeNullable(biomeRegistry.get(parentKey), plainsBiome);
        BiomeEffects parentEffects = parent.effects();
        Key key = Key.key("terra", createBiomeID(pack, source.getID()));

        // BiomeEffects in 26.1.1 is visual-color-only: waterColor, foliageColor,
        // dryFoliageColor, grassColor, grassColorModifier. Fog/sky/water-fog colors,
        // particles, sounds, and music moved to EnvironmentAttributes.
        BiomeEffects effects = BiomeEffects.builder()
            .waterColor(mergeNullable(properties.getWaterColor(), parentEffects.waterColor()))
            .foliageColor(mergeNullable(properties.getFoliageColor(), parentEffects.foliageColor()))
            .grassColor(mergeNullable(properties.getGrassColor(), parentEffects.grassColor()))
            .grassColorModifier(mergeNullable(properties.getGrassColorModifier(), parentEffects.grassColorModifier()))
            .build();

        Biome.Builder builder = Biome.builder()
            .precipitation(properties.getPrecipitation() != null ? properties.getPrecipitation() : parent.hasPrecipitation())
            .temperature(properties.getTemperature() != null ? properties.getTemperature() : parent.temperature())
            .temperatureModifier(mergeNullable(properties.getTemperatureModifier(), parent.temperatureModifier()))
            .downfall(properties.getDownfall() != null ? properties.getDownfall() : parent.downfall())
            .effects(effects);

        // Pack-overridable environment attributes. We only set attributes the user
        // explicitly provided; unset attributes fall back to Minestom defaults.
        // Parent-biome inheritance for these attributes is not yet preserved —
        // EnvironmentAttributeMap.entries() lookup is non-trivial to type-erase.
        setIfPresent(builder, attr("minecraft:visual/fog_color"), properties.getFogColor());
        setIfPresent(builder, attr("minecraft:visual/sky_color"), properties.getSkyColor());
        setIfPresent(builder, attr("minecraft:visual/water_fog_color"), properties.getWaterFogColor());
        setIfPresent(builder, attr("minecraft:visual/ambient_particles"), properties.getParticleConfig());

        // AmbientSounds is a composite of loop / mood / additions — assemble one
        // if the pack supplied any of them.
        if(properties.getLoopSound() != null
            || properties.getMoodSound() != null
            || properties.getAdditionsSound() != null) {
            AmbientSounds.Additions additions = properties.getAdditionsSound();
            builder.setAttribute(attr("minecraft:audio/ambient_sounds"), new AmbientSounds(
                properties.getLoopSound(),
                properties.getMoodSound(),
                additions != null ? List.of(additions) : List.of()
            ));
        }

        Biome target = builder.build();

        RegistryKey<Biome> registryKey = MinecraftServer.getBiomeRegistry().register(key, target);
        return new UserDefinedBiome(key, registryKey, source.getID(), target);
    }
}
