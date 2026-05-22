// Path A (no architectury) — NeoForge for MC 26.1, build via ModDevGradle.
//
// NeoForge replaced Forge after 1.20.4. MC 26.1 is unobfuscated, so no mappings
// declaration is needed and burningwave reflection (used in the old Forge
// platform to reach obfuscated fields) can be dropped entirely.
//
// This build script is parked at .disabled until the source tree
// (platforms/neoforge/src/main/java/com/dfsek/terra/neoforge/*) is migrated:
//   - net.minecraft.* yarn imports -> Mojang names
//   - net.minecraftforge.* -> net.neoforged.*
//   - Forge event bus / @Mod constructor patterns -> NeoForge ones
//   - ForgeRegistries.Keys -> BuiltInRegistries
//   - Drop AwfulForgeHacks reflection (unneeded in unobfuscated 26.1)
//
// See Multi-Platform-26.1-Plan.md section 2.4 for the migration plan.

plugins {
    id("net.neoforged.moddev") version Versions.NeoForge.modDevGradle
}

neoForge {
    version = Versions.NeoForge.neoForge

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

    // TODO Phase 4 source migration — re-enable once neoforge platform code
    // is rewritten against Mojang names + NeoForge API.
    // implementation(project(":platforms:mixin-common"))
    //
    // implementation("org.incendo", "cloud-neoforge", Versions.NeoForge.cloud) {
    //     exclude("me.lucko", "fabric-permissions-api")
    // }
}

tasks {
    jar {
        archiveBaseName.set("Terra-neoforge")
    }
}
