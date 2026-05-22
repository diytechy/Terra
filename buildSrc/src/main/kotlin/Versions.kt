object Versions {
    object Terra {
        const val chimeraConfig = "0.0.4"
        const val reimagENDConfig = "3.0.0"
        const val tartarusConfig = "1.0.0"
    }
    
    object Libraries {
        // Requires the diytechy Tectonic branch — Terra calls ConfigLoader.beginSession()/endSession()
        // which do not exist in upstream Tectonic. Resolved from mavenLocal (local dev) or Repsy diytechy (CI).
        const val tectonic = "4.3.2-diytechy"
        const val paralithic = "2.0.1"
        const val strata = "1.3.2"
        const val seismic = "2.5.7-PATCHED"
        
        const val cloud = "2.0.0"
        
        const val caffeine = "3.2.2"
        const val dendryTerra = "1.0.0-BETA-G"
        const val bubblesOnChunkGen = "1.2.2"

        const val slf4j = "2.0.17"

        object Internal {
            const val shadow = "8.3.9"
            const val apacheText = "1.14.0"
            const val apacheIO = "2.20.0"
            const val guava = "33.5.0-jre"
            const val asm = "9.9"
            const val snakeYml = "2.5"
            const val jetBrainsAnnotations = "26.0.2-1"
            const val junit = "6.0.0"
            const val nbt = "6.1"
        }
    }
    
    object Fabric {
        const val fabricAPI = "0.149.1+${Mod.minecraft}"
        const val cloud = "2.0.0-beta.15"
    }
//
//    object Quilt {
//        const val quiltLoader = "0.20.2"
//        const val fabricApi = "7.3.1+0.89.3-1.20.1"
//    }

    // MC 26.1 is the first unobfuscated release; Mojang's official mappings are now
    // canonical, and Fabric's old yarn mappings have been deprecated. The new
    // `net.fabricmc.fabric-loom` plugin builds against the unobfuscated jar directly.
    // Architectury has not yet ported to 26.1 (issue architectury-api#704 open
    // as of 2026-05-19), so this branch uses fabric-loom directly without
    // architectury's cross-platform abstraction.
    object Mod {
        const val mixin = "0.16.5+mixin.0.8.7"
        const val mixinExtras = "0.5.0"

        const val minecraft = "26.1.2"
        const val fabricLoader = "0.19.2"
        const val fabricLoom = "1.16.2"
    }
//
//    object Forge {
//        const val forge = "${Mod.minecraft}-48.0.13"
//        const val burningwave = "12.63.0"
//    }

    // NeoForge replaces Forge for MC 1.21+. Versioning is 4-component
    // <mcMajor>.<mcMinor>.<mcHotfix>.<neoforgeRelease>-<channel>.
    // MC 26.1 line is still in beta as of 2026-05; revisit when stable RC ships.
    // Requires Gradle 9.1.0+ and Java 25 (already in place).
    object NeoForge {
        const val neoForge = "26.1.2.59-beta"
        const val cloud = "2.0.0-beta.15"
        const val modDevGradle = "2.0.141"
    }

    object Bukkit {
        const val minecraft = "26.1"
        const val paper = "26.1.1.build.+"
        const val paperDevBundle = paper
        const val paperLib = "1.0.8"
        const val reflectionRemapper = "0.1.3"
        const val runPaper = "2.3.1"
        const val paperWeight = "2.0.0-beta.21"
        const val cloud = "2.0.0-beta.15"
        const val multiverse = "5.6.1"
    }
    
//
//    object Sponge {
//        const val sponge = "9.0.0-SNAPSHOT"
//        const val mixin = "0.8.2"
//        const val minecraft = "1.17.1"
//    }
//
    object CLI {
        const val logback = "1.5.19"
        const val picocli = "4.7.7"
    }
    
    object Allay {
        const val api = "0.20.0"
        const val gson = "2.13.2"
        
        const val mappings = "366baa6"
        const val mappingsGenerator = "e957088"
        
        const val mcmeta = "c976eb3"
    }
    
    object Minestom {
        const val minestom = "2026.05.17c-26.1.1"
    }
}
