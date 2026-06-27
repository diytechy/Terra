import org.gradle.api.Project
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.repositories
import java.util.concurrent.TimeUnit

fun Project.configureDependencies() {
    val testImplementation by configurations.getting
    val compileOnly by configurations.getting

    val api by configurations.getting
    val implementation by configurations.getting

    val shaded by configurations.creating

    @Suppress("UNUSED_VARIABLE")
    val shadedApi by configurations.creating {
        shaded.extendsFrom(this)
        api.extendsFrom(this)
    }

    @Suppress("UNUSED_VARIABLE")
    val shadedImplementation by configurations.creating {
        shaded.extendsFrom(this)
        implementation.extendsFrom(this)
    }

    repositories {
        mavenLocal()
        // diytechy fork of Tectonic — try local then Repsy before falling back to upstream.
        maven("https://repo.repsy.io/mvn/diytechy/tectonic") {
            name = "RepsyTectonic"
        }
        maven("https://repo.repsy.io/mvn/diytechy/seismic") {
            name = "RepsySeismic"
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.solo-studios.ca/releases") {
            name = "Solo Studios"
        }
        maven("https://maven.solo-studios.ca/snapshots") {
            name = "Solo Studios"
        }
        maven("https://maven.fabricmc.net/") {
            name = "FabricMC"
        }
        maven("https://repo.codemc.org/repository/maven-public") {
            name = "CodeMC"
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
        maven("https://maven.neoforged.net/releases/") {
            name = "NeoForged"
        }
        maven("https://maven.quiltmc.org/repository/release/") {
            name = "Quilt"
        }
        maven("https://jitpack.io") {
            name = "JitPack"
        }
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
            name = "Sonatype Snapshots"
        }
        maven("https://repo.onarandombox.com/multiverse-releases") {
            name = "onarandombox"
        }
        maven("https://repo.repsy.io/mvn/diytechy/dendryterra") {
            name = "DendryTerra"
        }
        maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft") {
            name = "RepsyCloudMinecraft"
        }
        maven("https://repo.repsy.io/mvn/diytechy/cloud-minecraft-modded") {
            name = "RepsyCloudMinecraftModded"
        }
        maven("https://repo.repsy.io/mvn/diytechy/terra-packs") {
            name = "TerraPacks"
        }
        maven("https://repo.repsy.io/mvn/diytechy/bubblesonchunkgen") {
            name = "BubblesOnChunkGen"
        }
    }
    
    dependencies {
        testImplementation("org.junit.jupiter", "junit-jupiter", Versions.Libraries.Internal.junit)
        "testRuntimeOnly"("org.junit.platform", "junit-platform-launcher")
        compileOnly("org.jetbrains", "annotations", Versions.Libraries.Internal.jetBrainsAnnotations)
        
        compileOnly("com.google.guava", "guava", Versions.Libraries.Internal.guava)
        testImplementation("com.google.guava", "guava", Versions.Libraries.Internal.guava)
    }

    // Local addon artifacts (e.g. BubblesOnChunkGen) are frequently rebuilt and re-published
    // to mavenLocal under the *same* release version. Gradle treats a fixed version as
    // immutable and caches it, so a plain `gradlew` build would keep using the stale jar.
    // Never cache "changing" modules so the latest mavenLocal content is always re-resolved;
    // the affected dependency is flagged `isChanging = true` at its declaration site.
    //
    // CHIMERA and the other config packs need nothing here: the `downloadDefaultPacks` task
    // copies them straight from ~/.m2 on every build (no Gradle module cache involved).
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
        resolutionStrategy.cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}
