package com.dfsek.terra.mod.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.DimensionType.MonsterSettings;
import org.jetbrains.annotations.NotNull;

import com.dfsek.terra.mod.ModPlatform;
import com.dfsek.terra.mod.config.MonsterSettingsConfig;
import com.dfsek.terra.mod.config.VanillaWorldProperties;
import com.dfsek.terra.mod.implmentation.TerraIntProvider;


public class DimensionUtil {
    public static DimensionType createDimension(VanillaWorldProperties vanillaWorldProperties, DimensionType defaultDimension,
                                                ModPlatform platform) {

        MonsterSettingsConfig monsterSettingsConfig;
        if(vanillaWorldProperties.getMonsterSettings() != null) {
            monsterSettingsConfig = vanillaWorldProperties.getMonsterSettings();
        } else {
            monsterSettingsConfig = new MonsterSettingsConfig();
        }

        MonsterSettings monsterSettings = getMonsterSettings(defaultDimension, monsterSettingsConfig);

        // 26.1 moved ultra-warm / bed-works / respawn-anchor-works / cloud-height out of DimensionType
        // and into its EnvironmentAttributeMap. Re-apply the configurable ones on top of the base
        // dimension's attributes (mirrors how BiomeUtil.createBiome was restored).
        // Deliberately deferred (carried from the base dimension unchanged): fixed-time (now
        // timelines/defaultClock), natural, and effects (now skybox + cardinal-lighting). See
        // investigations/Fabric-Yarn-to-Mojang-Migration.md for the rationale.
        EnvironmentAttributeMap attributes = buildAttributes(vanillaWorldProperties, defaultDimension);

        return new DimensionType(
            defaultDimension.hasFixedTime(),
            vanillaWorldProperties.getHasSkyLight() == null ? defaultDimension.hasSkyLight() : vanillaWorldProperties.getHasSkyLight(),
            vanillaWorldProperties.getHasCeiling() == null ? defaultDimension.hasCeiling() : vanillaWorldProperties.getHasCeiling(),
            defaultDimension.hasEnderDragonFight(),
            vanillaWorldProperties.getCoordinateScale() == null
            ? defaultDimension.coordinateScale()
            : vanillaWorldProperties.getCoordinateScale(),
            vanillaWorldProperties.getHeight() == null ? defaultDimension.minY() : vanillaWorldProperties.getHeight().getMin(),
            vanillaWorldProperties.getHeight() == null ? defaultDimension.height() : vanillaWorldProperties.getHeight().getRange(),
            vanillaWorldProperties.getLogicalHeight() == null
            ? defaultDimension.logicalHeight()
            : vanillaWorldProperties.getLogicalHeight(),
            vanillaWorldProperties.getInfiniburn() == null
            ? defaultDimension.infiniburn()
            : TagKey.create(Registries.BLOCK, vanillaWorldProperties.getInfiniburn()),
            vanillaWorldProperties.getAmbientLight() == null ? defaultDimension.ambientLight() : vanillaWorldProperties.getAmbientLight(),
            monsterSettings,
            defaultDimension.skybox(),
            defaultDimension.cardinalLightType(),
            attributes,
            defaultDimension.timelines(),
            defaultDimension.defaultClock()
        );
    }

    private static EnvironmentAttributeMap buildAttributes(VanillaWorldProperties props, DimensionType defaultDimension) {
        EnvironmentAttributeMap.Builder attrs = EnvironmentAttributeMap.builder()
            .putAll(defaultDimension.attributes()); // carry the base dimension's vanilla defaults

        if(props.getRespawnAnchorWorks() != null) {
            attrs.set(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, props.getRespawnAnchorWorks());
        }
        if(props.getCloudHeight() != null) {
            attrs.set(EnvironmentAttributes.CLOUD_HEIGHT, props.getCloudHeight().floatValue());
        }
        if(props.getBedWorks() != null) {
            attrs.set(EnvironmentAttributes.BED_RULE,
                props.getBedWorks() ? BedRule.CAN_SLEEP_WHEN_DARK : BedRule.EXPLODES);
        }
        if(props.getUltraWarm() != null) {
            // ultra-warm was a single flag in pre-26.1; it now decomposes into these three attributes.
            boolean ultraWarm = props.getUltraWarm();
            attrs.set(EnvironmentAttributes.WATER_EVAPORATES, ultraWarm);
            attrs.set(EnvironmentAttributes.FAST_LAVA, ultraWarm);
            attrs.set(EnvironmentAttributes.INCREASED_FIRE_BURNOUT, ultraWarm);
        }

        return attrs.build();
    }

    @NotNull
    private static MonsterSettings getMonsterSettings(DimensionType defaultDimension, MonsterSettingsConfig monsterSettingsConfig) {
        MonsterSettings defaultMonsterSettings = defaultDimension.monsterSettings();

        return new MonsterSettings(
            monsterSettingsConfig.getMonsterSpawnLight() == null ? defaultMonsterSettings.monsterSpawnLightTest() : new TerraIntProvider(
                monsterSettingsConfig.getMonsterSpawnLight()),
            monsterSettingsConfig.getMonsterSpawnBlockLightLimit() == null
            ? defaultMonsterSettings.monsterSpawnBlockLightLimit()
            : monsterSettingsConfig.getMonsterSpawnBlockLightLimit()
        );
    }
}
