package com.dfsek.terra.mod.mixin.access;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;


// Biome.ClimateSettings is package-private, so it is targeted by name; instances are duck-cast
// to this interface to read the values BiomeUtil needs when cloning a vanilla biome.
@Mixin(targets = "net.minecraft.world.level.biome.Biome$ClimateSettings")
public interface ClimateSettingsAccessor {
    @Invoker("downfall")
    float invokeDownfall();

    @Invoker("temperatureModifier")
    Biome.TemperatureModifier invokeTemperatureModifier();
}
