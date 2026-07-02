package com.dfsek.terra.mod.mixin.access;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(Biome.class)
public interface BiomeAccessor {
    // Biome.ClimateSettings is package-private and cannot be named here, so the accessor is typed
    // as Object and duck-cast to ClimateSettingsAccessor by the caller.
    @Accessor("climateSettings")
    Object getWeather();
}
