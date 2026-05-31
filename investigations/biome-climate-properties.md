# Biome `climate` Block — What Terra Does With It

## Summary

The `climate` block in a Terra biome template is **pass-through metadata**: Terra does not consume it for terrain generation, biome selection, or block placement. It is forwarded to the platform-side Minecraft biome registration and consumed by vanilla systems (weather, foliage tint, mob spawn temperature checks, water freezing).

## Example

```yaml
climate:
  precipitation: false
  temperature: 2.0
  downfall: 0.0
```

## Field meanings (vanilla Minecraft semantics)

- **`precipitation`** (`Boolean`) — whether weather (rain/snow) can occur in the biome. `false` disables all precipitation. Defaults to the vanilla parent biome's value if omitted.
- **`temperature`** (`Float`) — vanilla base temperature. Controls:
  - Snow vs. rain (roughly ≤0.15 → snow)
  - Water/ice freezing
  - Foliage & grass color tint (via the vanilla color grid)
  - Mob spawn temperature gating
  - `2.0` is hot (desert-like; no freezing, yellowed grass).
- **`temperature-modifier`** (enum) — vanilla `Biome.TemperatureModifier` (e.g. `FROZEN`). Adjusts temperature sampling per-block (frozen oceans etc.).
- **`downfall`** (`Float`) — humidity. Historically rainfall intensity; in modern MC mostly drives foliage humidity tint and a few particle/ambient effects.

## Config binding

Declared in `VanillaBiomeProperties` on each platform:
- [platforms/mixin-common/src/main/java/com/dfsek/terra/mod/config/VanillaBiomeProperties.java:57-71](platforms/mixin-common/src/main/java/com/dfsek/terra/mod/config/VanillaBiomeProperties.java#L57-L71)
- [platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/config/VanillaBiomeProperties.java](platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/config/VanillaBiomeProperties.java)
- [platforms/minestom/src/main/java/com/dfsek/terra/minestom/config/VanillaBiomeProperties.java](platforms/minestom/src/main/java/com/dfsek/terra/minestom/config/VanillaBiomeProperties.java)

## Where it is applied

When Terra builds the platform-side biome, it writes the values into the Minecraft biome builder, falling back to the vanilla parent biome's values if unset:

- Fabric/mixin: [platforms/mixin-common/src/main/java/com/dfsek/terra/mod/util/BiomeUtil.java:86-93](platforms/mixin-common/src/main/java/com/dfsek/terra/mod/util/BiomeUtil.java#L86-L93)
- Bukkit NMS: [platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/NMSBiomeInjector.java:87-94](platforms/bukkit/nms/src/main/java/com/dfsek/terra/bukkit/nms/NMSBiomeInjector.java#L87-L94)
- Minestom: [platforms/minestom/src/main/java/com/dfsek/terra/minestom/biome/MinestomUserDefinedBiomeFactory.java:64-67](platforms/minestom/src/main/java/com/dfsek/terra/minestom/biome/MinestomUserDefinedBiomeFactory.java#L64-L67)

Pattern (fabric example):

```java
builder.precipitation(Objects.requireNonNullElse(vanillaBiomeProperties.getPrecipitation(), vanilla.hasPrecipitation()));
builder.temperature(Objects.requireNonNullElse(vanillaBiomeProperties.getTemperature(),   vanilla.getTemperature()));
builder.downfall(   Objects.requireNonNullElse(vanillaBiomeProperties.getDownfall(),      vanilla.climateSettings.downfall()));
builder.temperatureModifier(Objects.requireNonNullElse(vanillaBiomeProperties.getTemperatureModifier(), vanilla.climateSettings.temperatureModifier()));
```

## Implications for pack authors

- Changing `climate.temperature` does **not** affect which Terra biome is chosen at a location — Terra's biome distribution is independent (see `TerrainGenerationPipeline.md`).
- It **does** affect visuals (grass/foliage tint) and gameplay (snow, freezing, mob spawns) via vanilla systems.
- To force a visual look (e.g. always-snow or desert tint) without changing terrain shape, set `climate.temperature` appropriately and leave terrain samplers untouched.
