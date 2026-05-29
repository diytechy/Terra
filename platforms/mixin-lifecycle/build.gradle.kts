// Path A — fabric-loom directly, no architectury wrapper.
plugins {
    id("net.fabricmc.fabric-loom") version Versions.Mod.fabricLoom
}

loom {
    accessWidenerPath.set(project(":platforms:mixin-common").file("src/main/resources/terra.accesswidener"))

    mixin {
        defaultRefmapName.set("terra.lifecycle.refmap.json")
        // 26.1 is non-obfuscated: disable the legacy compile-time mixin AP (it has no obf map and
        // fatally errors on named @Inject targets). Loom builds the refmap at remap time instead.
        useLegacyMixinAp.set(false)
    }
}

dependencies {
    shadedApi(project(":common:implementation:base"))

    compileOnly("net.fabricmc:sponge-mixin:${Versions.Mod.mixin}")
    compileOnly("io.github.llamalad7:mixinextras-common:${Versions.Mod.mixinExtras}")

    implementation(project(":platforms:mixin-common"))

    minecraft("com.mojang:minecraft:${Versions.Mod.minecraft}")

    implementation("net.fabricmc:fabric-loader:${Versions.Mod.fabricLoader}")
    implementation("org.incendo", "cloud-fabric", Versions.Fabric.cloud)
}
