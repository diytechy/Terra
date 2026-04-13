version = version(Versions.Libraries.bubblesOnChunkGen)

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
    api("com.bubbleschunkgen", "bubbleschunkgen-terra-addon", Versions.Libraries.bubblesOnChunkGen)
}
