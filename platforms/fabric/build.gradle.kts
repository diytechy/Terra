import java.util.*

// Path A — fabric-loom 1.15 directly, no architectury wrapper.
// MC 26.1 is unobfuscated — no `mappings(...)` block needed and `modImplementation`
// /`modRuntimeOnly` have been replaced by plain `implementation`/`runtimeOnly`.
plugins {
    id("net.fabricmc.fabric-loom") version Versions.Mod.fabricLoom
}

dependencies {
    shadedApi(project(":common:implementation:base"))

    implementation(project(":platforms:mixin-common"))
    implementation(project(":platforms:mixin-lifecycle"))

    minecraft("com.mojang:minecraft:${Versions.Mod.minecraft}")

    implementation("net.fabricmc:fabric-loader:${Versions.Mod.fabricLoader}")

    // Confirmed 2026-05-28: cloud-fabric beta.16 (first 26.1-supporting release) resolves
    // and loom 1.16 accepts its access widener — the intermediary-namespace rejection seen
    // with beta.15 is gone. Re-enable this + the mixin subprojects only once the yarn->Mojang
    // source rename in mixin-common is done (otherwise compileJava fails with ~100 errors).
    implementation("org.incendo", "cloud-fabric", Versions.Fabric.cloud)
    runtimeOnly("net.fabricmc.fabric-api", "fabric-api", Versions.Fabric.fabricAPI)
}

loom {
    accessWidenerPath.set(project(":platforms:mixin-common").file("src/main/resources/terra.accesswidener"))

    mixin {
        defaultRefmapName.set("terra.fabric.refmap.json")
    }
}


addonDir(project.file("./run/config/Terra/addons"), tasks.named("configureLaunch").get())

tasks {
    jar {
        dependsOn("installAddons")
        archiveFileName.set("${rootProject.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}-fabric-${project.version}.jar")
    }
}
