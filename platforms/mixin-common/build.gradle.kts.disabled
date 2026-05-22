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
    }
}

dependencies {
    shadedApi(project(":common:implementation:base"))

    compileOnly("net.fabricmc:sponge-mixin:${Versions.Mod.mixin}")
    annotationProcessor("net.fabricmc:sponge-mixin:${Versions.Mod.mixin}")

    minecraft("com.mojang:minecraft:${Versions.Mod.minecraft}")
}
