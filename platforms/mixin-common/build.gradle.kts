// Path A — fabric-loom directly, no architectury wrapper.
// MC 26.1 is unobfuscated; mappings are resolved automatically by loom from
// the Mojang jar — no yarn dependency.
plugins {
    id("net.fabricmc.fabric-loom") version Versions.Mod.fabricLoom
}

loom {
    accessWidenerPath.set(file("src/main/resources/terra.accesswidener"))

    mixin {
        defaultRefmapName.set("terra.common.refmap.json")
        // 26.1 is non-obfuscated: the legacy sponge-mixin AP has no obf map and fatally errors
        // on named @Inject targets. Disable it (and the manual AP dependency) so loom builds the
        // refmap at remap time instead. Trade-off: mixin targets are validated at runtime, not compile.
        useLegacyMixinAp.set(false)
    }
}

dependencies {
    shadedApi(project(":common:implementation:base"))

    compileOnly("net.fabricmc:sponge-mixin:${Versions.Mod.mixin}")

    minecraft("com.mojang:minecraft:${Versions.Mod.minecraft}")
}
