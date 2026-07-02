package com.dfsek.terra.mod.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.BiomeGenerationSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.mod.config.VanillaBiomeProperties;
import com.dfsek.terra.mod.mixin.access.BiomeAccessor;
import com.dfsek.terra.mod.mixin.access.ClimateSettingsAccessor;


public class BiomeUtil {
    public static final Map<Identifier, List<Identifier>>
        TERRA_BIOME_MAP = new HashMap<>();

    public static Biome createBiome(Biome vanilla, VanillaBiomeProperties vanillaBiomeProperties) {
        BiomeSpecialEffects vanillaEffects = vanilla.getSpecialEffects();
        BiomeSpecialEffects.Builder effects = new BiomeSpecialEffects.Builder();

        effects.waterColor(Objects.requireNonNullElse(vanillaBiomeProperties.getWaterColor(), vanillaEffects.waterColor()))
            .grassColorModifier(
                Objects.requireNonNullElse(vanillaBiomeProperties.getGrassColorModifier(), vanillaEffects.grassColorModifier()));

        if(vanillaBiomeProperties.getGrassColor() == null) {
            vanillaEffects.grassColorOverride().ifPresent(effects::grassColorOverride);
        } else {
            effects.grassColorOverride(vanillaBiomeProperties.getGrassColor());
        }

        if(vanillaBiomeProperties.getFoliageColor() == null) {
            vanillaEffects.foliageColorOverride().ifPresent(effects::foliageColorOverride);
        } else {
            effects.foliageColorOverride(vanillaBiomeProperties.getFoliageColor());
        }

        if(vanillaBiomeProperties.getDryFoliageColor() == null) {
            vanillaEffects.dryFoliageColorOverride().ifPresent(effects::dryFoliageColorOverride);
        } else {
            effects.dryFoliageColorOverride(vanillaBiomeProperties.getDryFoliageColor());
        }

        Biome.BiomeBuilder builder = new Biome.BiomeBuilder();
        // Fog/sky/water-fog colors, music, particles and ambient sounds became environment
        // attributes in 26.1; carry vanilla's wholesale, then re-apply the config overrides
        // onto the attribute map (each only when the pack actually specifies it).
        builder.putAttributes(vanilla.getAttributes());

        if(vanillaBiomeProperties.getFogColor() != null) {
            builder.setAttribute(EnvironmentAttributes.FOG_COLOR, vanillaBiomeProperties.getFogColor());
        }
        if(vanillaBiomeProperties.getWaterFogColor() != null) {
            builder.setAttribute(EnvironmentAttributes.WATER_FOG_COLOR, vanillaBiomeProperties.getWaterFogColor());
        }
        if(vanillaBiomeProperties.getSkyColor() != null) {
            builder.setAttribute(EnvironmentAttributes.SKY_COLOR, vanillaBiomeProperties.getSkyColor());
        }
        if(vanillaBiomeProperties.getMusicVolume() != null) {
            builder.setAttribute(EnvironmentAttributes.MUSIC_VOLUME, vanillaBiomeProperties.getMusicVolume());
        }
        if(vanillaBiomeProperties.getMusic() != null) {
            builder.setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(vanillaBiomeProperties.getMusic()));
        }
        if(vanillaBiomeProperties.getParticleConfig() != null) {
            builder.setAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, List.of(vanillaBiomeProperties.getParticleConfig()));
        }
        if(vanillaBiomeProperties.getLoopSound() != null || vanillaBiomeProperties.getMoodSound() != null ||
           vanillaBiomeProperties.getAdditionsSound() != null) {
            builder.setAttribute(EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(
                vanillaBiomeProperties.getLoopSound() == null
                ? Optional.empty()
                : Optional.of(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(vanillaBiomeProperties.getLoopSound())),
                Optional.ofNullable(vanillaBiomeProperties.getMoodSound()),
                vanillaBiomeProperties.getAdditionsSound() == null
                ? List.of()
                : List.of(vanillaBiomeProperties.getAdditionsSound())));
        }

        ClimateSettingsAccessor vanillaClimate = (ClimateSettingsAccessor) ((BiomeAccessor) ((Object) vanilla)).getWeather();
        builder.hasPrecipitation(Objects.requireNonNullElse(vanillaBiomeProperties.getPrecipitation(), vanilla.hasPrecipitation()));
        builder.temperature(Objects.requireNonNullElse(vanillaBiomeProperties.getTemperature(), vanilla.getBaseTemperature()));
        builder.downfall(Objects.requireNonNullElse(vanillaBiomeProperties.getDownfall(), vanillaClimate.invokeDownfall()));
        builder.temperatureAdjustment(Objects.requireNonNullElse(vanillaBiomeProperties.getTemperatureModifier(),
            vanillaClimate.invokeTemperatureModifier()));
        builder.mobSpawnSettings(Objects.requireNonNullElse(vanillaBiomeProperties.getSpawnSettings(), vanilla.getMobSettings()));

        return builder
            .specialEffects(effects.build())
            .generationSettings(BiomeGenerationSettings.EMPTY)
            .build();
    }

    public static String createBiomeID(ConfigPack pack, com.dfsek.terra.api.registry.key.RegistryKey biomeID) {
        return pack.getID()
                   .toLowerCase() + "/" + biomeID.getNamespace().toLowerCase(Locale.ROOT) + "/" + biomeID.getID().toLowerCase(Locale.ROOT);
    }

    public static Map<Identifier, List<Identifier>> getTerraBiomeMap() {
        return Map.copyOf(TERRA_BIOME_MAP);
    }
}
