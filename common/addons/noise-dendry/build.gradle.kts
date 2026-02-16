version = version("1.0.0")

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
    api("com.github.diytechy", "dendryterra", Versions.Libraries.dendryTerra)
}
