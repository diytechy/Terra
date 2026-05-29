package com.dfsek.terra.mod.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.DimensionType.MonsterSettings;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalLong;

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

        // 26.1 overhauled DimensionType: time/ultrawarm/natural/bed/respawn-anchor/effects/cloud-height
        // became skybox + cardinal-lighting + environment attributes + timelines, which are copied
        // wholesale from the default dimension. Only the height/infiniburn/ambient overrides remain.
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
            defaultDimension.attributes(),
            defaultDimension.timelines(),
            defaultDimension.defaultClock()
        );
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
