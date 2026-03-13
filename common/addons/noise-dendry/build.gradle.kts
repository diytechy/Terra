version = version(Versions.Libraries.dendryTerra)

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
    api("com.github.diytechy", "dendryterra", Versions.Libraries.dendryTerra)
}
