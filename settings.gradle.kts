rootProject.name = "Terra"


// Platforms whose build has not been ported to the MC 26.2 toolchain (fabric-loom /
// unobfuscated mappings). They cannot configure against MC 26.2 and are excluded from
// the build until ported.
val unportedPlatforms = setOf("fabric", "minestom")

fun includeImmediateChildren(dir: File, type: String, skip: Set<String> = emptySet()) {
    dir.walkTopDown().maxDepth(1).forEach {
        if (!it.isDirectory || !File(it, "build.gradle.kts").exists()) return@forEach
        if (it.name in skip) return@forEach
        val addonDir = it.relativeTo(file(".")).path.replace("/", ":").replace("\\", ":")
        logger.info("Including $type directory \"$addonDir\" as subproject.")
        include(addonDir)
    }
}

includeImmediateChildren(file("common/api"), "API")

includeImmediateChildren(file("common/implementation"), "implementation")

includeImmediateChildren(file("common/addons"), "addon")

includeImmediateChildren(file("platforms"), "platform", unportedPlatforms)

includeImmediateChildren(file("platforms/bukkit/nms"), "Bukkit NMS")

include(":platforms:bukkit:common")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.solo-studios.ca/releases") {
            name = "Solo Studios"
        }
        maven("https://maven.solo-studios.ca/snapshots") {
            name = "Solo Studios"
        }
        maven("https://maven.fabricmc.net") {
            name = "Fabric Maven"
        }
        maven("https://maven.architectury.dev/") {
            name = "Architectury Maven"
        }
        maven("https://maven.neoforged.net/releases/") {
            name = "NeoForged Maven"
        }
        maven("https://maven.quiltmc.org/repository/release/") {
            name = "Quilt"
        }
    }
}

// settings.gradle.kts
val isCiServer = System.getenv().containsKey("CI")
// Cache build artifacts, so expensive operations do not need to be re-computed
buildCache {
    local {
        isEnabled = !isCiServer
    }
}