package com.dfsek.terra.mod.util;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.config.MetaPack;
import com.dfsek.terra.api.util.generic.pair.Pair;
import com.dfsek.terra.api.util.range.ConstantRange;
import com.dfsek.terra.mod.ModPlatform;
import com.dfsek.terra.mod.config.VanillaWorldProperties;
import com.dfsek.terra.mod.generation.GenerationSettings;
import com.dfsek.terra.mod.generation.MinecraftChunkGeneratorWrapper;
import com.dfsek.terra.mod.generation.TerraBiomeSource;


public class PresetUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresetUtil.class);
    private static final List<Pair<Identifier, Boolean>> PRESETS = new ArrayList<>();

    public static Pair<Identifier, WorldPreset> createDefault(ConfigPack pack, ModPlatform platform, boolean extended,
                                                              boolean packInMetapack) {
        Registry<DimensionType> dimensionTypeRegistry = platform.dimensionTypeRegistry();
        Registry<NoiseGeneratorSettings> chunkGeneratorSettingsRegistry = platform.chunkGeneratorSettingsRegistry();
        Registry<MultiNoiseBiomeSourceParameterList> multiNoiseBiomeSourceParameterLists =
            platform.multiNoiseBiomeSourceParameterListRegistry();


        Identifier generatorID = Identifier.tryParse(
            "terra:" + pack.getID().toLowerCase(Locale.ROOT) + "/" + pack.getNamespace().toLowerCase(
                Locale.ROOT));

        PRESETS.add(Pair.of(generatorID, extended));

        HashMap<ResourceKey<LevelStem>, LevelStem> dimensionMap = new HashMap<>();

        insertCustom(platform, "minecraft:overworld", pack, dimensionTypeRegistry, chunkGeneratorSettingsRegistry, dimensionMap,
            packInMetapack);

        insertDefaults(dimensionTypeRegistry, chunkGeneratorSettingsRegistry, multiNoiseBiomeSourceParameterLists, platform.biomeRegistry(),
            dimensionMap);

        WorldPreset preset = new WorldPreset(dimensionMap);
        LOGGER.info("Created world type \"{}\"", generatorID);
        return Pair.of(generatorID, preset);
    }

    public static Pair<Identifier, WorldPreset> createMetaPackPreset(MetaPack metaPack, ModPlatform platform, boolean extended) {
        Registry<DimensionType> dimensionTypeRegistry = platform.dimensionTypeRegistry();
        Registry<NoiseGeneratorSettings> chunkGeneratorSettingsRegistry = platform.chunkGeneratorSettingsRegistry();
        Registry<MultiNoiseBiomeSourceParameterList> multiNoiseBiomeSourceParameterLists =
            platform.multiNoiseBiomeSourceParameterListRegistry();

        Identifier generatorID = Identifier.of("terra",
            metaPack.getID().toLowerCase(Locale.ROOT) + "/" + metaPack.getNamespace().toLowerCase(
                Locale.ROOT));

        PRESETS.add(Pair.of(generatorID, extended));

        HashMap<ResourceKey<LevelStem>, LevelStem> dimensionMap = new HashMap<>();

        metaPack.packs().forEach((key, pack) -> {
            insertCustom(platform, key, pack, dimensionTypeRegistry, chunkGeneratorSettingsRegistry, dimensionMap, false);
        });

        insertDefaults(dimensionTypeRegistry, chunkGeneratorSettingsRegistry, multiNoiseBiomeSourceParameterLists, platform.biomeRegistry(),
            dimensionMap);

        WorldPreset preset = new WorldPreset(dimensionMap);
        LOGGER.info("Created world type \"{}\"", generatorID);
        return Pair.of(generatorID, preset);
    }

    private static void insertCustom(ModPlatform platform, String key, ConfigPack pack, Registry<DimensionType> dimensionTypeRegistry,
                                     Registry<NoiseGeneratorSettings> chunkGeneratorSettingsRegistry,
                                     HashMap<ResourceKey<LevelStem>, LevelStem> dimensionMap, boolean packInMetapack) {
        Identifier demensionIdentifier = Identifier.of(key);

        VanillaWorldProperties vanillaWorldProperties;

        if(pack.getContext().has(VanillaWorldProperties.class)) {
            vanillaWorldProperties = pack.getContext().get(VanillaWorldProperties.class);
        } else {
            vanillaWorldProperties = new VanillaWorldProperties();
        }

        DimensionType defaultDimension = dimensionTypeRegistry.get(Identifier.of(vanillaWorldProperties.getVanillaDimension()));

        assert defaultDimension != null;

        Identifier dimensionTypeID = Identifier.of("terra", pack.getID().toLowerCase(Locale.ROOT));

        DimensionType dimensionType;
        if(!packInMetapack) {
            dimensionType = DimensionUtil.createDimension(vanillaWorldProperties, defaultDimension, platform);
            ResourceKey<DimensionType> dimensionTypeRegistryKey = MinecraftUtil.registerDimensionTypeKey(
                dimensionTypeID);

            Registry.registerReference(dimensionTypeRegistry, dimensionTypeRegistryKey, dimensionType);
        } else {
            dimensionType = dimensionTypeRegistry.get(dimensionTypeID);
        }

        Holder<DimensionType> dimensionTypeRegistryEntry = dimensionTypeRegistry.getEntry(dimensionType);

        TerraBiomeSource biomeSource = new TerraBiomeSource(pack);

        Holder<NoiseGeneratorSettings> defaultGeneratorSettings = chunkGeneratorSettingsRegistry.getEntry(
            chunkGeneratorSettingsRegistry.get(Identifier.of(vanillaWorldProperties.getVanillaGeneration())));

        GenerationSettings generatorSettings = new GenerationSettings(
            vanillaWorldProperties.getHeight() == null ? new ConstantRange(
                defaultGeneratorSettings.value().generationShapeConfig().minimumY(),
                defaultGeneratorSettings.value().generationShapeConfig().height()) : vanillaWorldProperties.getHeight(),
            vanillaWorldProperties.getSealevel() == null
            ? defaultGeneratorSettings.value().seaLevel()
            : vanillaWorldProperties.getSealevel(),
            vanillaWorldProperties.getMobGeneration() == null
            ? !defaultGeneratorSettings.value().mobGenerationDisabled()
            : vanillaWorldProperties.getMobGeneration(),
            vanillaWorldProperties.getSpawnHeight());

        ChunkGenerator generator = new MinecraftChunkGeneratorWrapper(biomeSource, pack, generatorSettings);

        LevelStem dimensionOptions = new LevelStem(dimensionTypeRegistryEntry, generator);
        ResourceKey<LevelStem> dimensionOptionsRegistryKey = ResourceKey.of(Registries.DIMENSION, demensionIdentifier);
        dimensionMap.put(dimensionOptionsRegistryKey, dimensionOptions);
    }

    private static void insertDefaults(Registry<DimensionType> dimensionTypeRegistry,
                                       Registry<NoiseGeneratorSettings> chunkGeneratorSettingsRegistry,
                                       Registry<MultiNoiseBiomeSourceParameterList> multiNoiseBiomeSourceParameterLists,
                                       Registry<Biome> biomeRegistry, HashMap<ResourceKey<LevelStem>, LevelStem> map) {
        if(!map.containsKey(LevelStem.OVERWORLD)) {
            Holder<DimensionType> overworldDimensionType = dimensionTypeRegistry.getEntry(
                dimensionTypeRegistry.get(BuiltinDimensionTypes.OVERWORLD));

            Holder<MultiNoiseBiomeSourceParameterList> overworldChunkBiomeReference =
                multiNoiseBiomeSourceParameterLists.getEntry(multiNoiseBiomeSourceParameterLists.get(
                    MultiNoiseBiomeSourceParameterLists.OVERWORLD));

            Holder<NoiseGeneratorSettings> overworldChunkGeneratorSettings = chunkGeneratorSettingsRegistry.getEntry(
                chunkGeneratorSettingsRegistry.get(
                    NoiseGeneratorSettings.OVERWORLD));


            LevelStem overworldDimensionOptions = new LevelStem(overworldDimensionType,
                (new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.create(overworldChunkBiomeReference), overworldChunkGeneratorSettings)));
            map.put(LevelStem.OVERWORLD, overworldDimensionOptions);
        }
        if(!map.containsKey(LevelStem.NETHER)) {
            Holder<DimensionType> netherDimensionType = dimensionTypeRegistry.getEntry(
                dimensionTypeRegistry.get(BuiltinDimensionTypes.THE_NETHER));

            Holder<MultiNoiseBiomeSourceParameterList> netherChunkBiomeReference =
                multiNoiseBiomeSourceParameterLists.getEntry(multiNoiseBiomeSourceParameterLists.get(
                    MultiNoiseBiomeSourceParameterLists.NETHER));

            Holder<NoiseGeneratorSettings> netherChunkGeneratorSettings = chunkGeneratorSettingsRegistry.getEntry(
                chunkGeneratorSettingsRegistry.get(
                    NoiseGeneratorSettings.NETHER));

            LevelStem overworldDimensionOptions = new LevelStem(netherDimensionType,
                (new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.create(netherChunkBiomeReference), netherChunkGeneratorSettings)));
            map.put(LevelStem.NETHER, overworldDimensionOptions);
        }
        if(!map.containsKey(LevelStem.END)) {
            Holder<DimensionType> endDimensionType = dimensionTypeRegistry.getEntry(
                dimensionTypeRegistry.get(BuiltinDimensionTypes.THE_END));

            Holder<NoiseGeneratorSettings> endChunkGeneratorSettings = chunkGeneratorSettingsRegistry.getEntry(
                chunkGeneratorSettingsRegistry.get(
                    NoiseGeneratorSettings.END));


            LevelStem overworldDimensionOptions = new LevelStem(endDimensionType,
                (new NoiseBasedChunkGenerator(TheEndBiomeSource.createVanilla(biomeRegistry), endChunkGeneratorSettings)));
            map.put(LevelStem.END, overworldDimensionOptions);
        }
    }

    public static List<Pair<Identifier, Boolean>> getPresets() {
        return PRESETS;
    }
}
