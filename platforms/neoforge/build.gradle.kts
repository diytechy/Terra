// Path A (no architectury) — NeoForge for MC 26.1, build via ModDevGradle.
//
// NeoForge replaced Forge after 1.20.4. MC 26.1 is unobfuscated, so no mappings
// declaration is needed and burningwave reflection (used in the old Forge
// platform to reach obfuscated fields) is dropped.
//
// Architecture (Path A1): the platform reuses the loader-neutral mixin-common +
// mixin-lifecycle modules. NeoForge runs the identical Minecraft RegistryDataLoader /
// BuiltInRegistries / WorldLoader, so the same mixins that register Terra's biomes +
// codecs on Fabric apply here. The old RegisterEvent-based registration was invalid
// for 26.1 (RegisterEvent only fires for builtin, not dynamic, registries).

plugins {
    id("net.neoforged.moddev") version Versions.NeoForge.modDevGradle
}

neoForge {
    version = Versions.NeoForge.neoForge

    // AT equivalent of mixin-common's terra.accesswidener (Biome.ClimateSettings -> public).
    accessTransformers.from("src/main/resources/META-INF/accesstransformer.cfg")

    mods {
        register("terra") {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
        }
    }
}

dependencies {
    shadedApi(project(":common:implementation:base"))

    // Reuse the loader-neutral shared modules (Path A1). shadedImplementation bundles their
    // classes + *.mixins.json + accesswidener into the shadowJar (same packaging path as Fabric).
    shadedImplementation(project(":platforms:mixin-common"))
    shadedImplementation(project(":platforms:mixin-lifecycle"))

    implementation("org.incendo", "cloud-neoforge", Versions.NeoForge.cloud) {
        exclude("me.lucko", "fabric-permissions-api")
    }

    // cloud-core 2.0.0 was compiled with org.immutables annotations; without this on the
    // classpath javac emits "Cannot find annotation method" classfile warnings.
    compileOnly("org.immutables", "value", "2.10.1") { isTransitive = false }
}

tasks {
    jar {
        archiveBaseName.set("Terra-neoforge")
    }
}
