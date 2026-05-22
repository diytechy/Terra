// Path A — fabric-loom directly, no architectury wrapper.
plugins {
    id("net.fabricmc.fabric-loom") version Versions.Mod.fabricLoom
}

loom {
    accessWidenerPath.set(project(":platforms:mixin-common").file("src/main/resources/terra.accesswidener"))

    mixin {
        defaultRefmapName.set("terra.lifecycle.refmap.json")
    }
}

dependencies {
    shadedApi(project(":common:implementation:base"))

    compileOnly("net.fabricmc:sponge-mixin:${Versions.Mod.mixin}")
    compileOnly("io.github.llamalad7:mixinextras-common:${Versions.Mod.mixinExtras}")
    annotationProcessor("net.fabricmc:sponge-mixin:${Versions.Mod.mixin}")

    implementation(project(":platforms:mixin-common"))

    minecraft("com.mojang:minecraft:${Versions.Mod.minecraft}")

    // TODO Phase 3 — re-add cloud-fabric once its AW namespace is migrated to official
    // implementation("org.incendo", "cloud-fabric", Versions.Fabric.cloud) { ... }
}
