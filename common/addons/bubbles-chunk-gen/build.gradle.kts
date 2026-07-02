version = version(Versions.Libraries.bubblesOnChunkGen)

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
    // isChanging: re-published to mavenLocal under the same version on every rev, so Gradle
    // must re-resolve it each build rather than trusting its immutable-version cache. Paired
    // with cacheChangingModulesFor(0) in DependencyConfig.kt.
    api("com.bubbleschunkgen", "bubbleschunkgen-terra-addon", Versions.Libraries.bubblesOnChunkGen) {
        isChanging = true
    }
}
