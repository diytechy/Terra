version = version(Versions.Libraries.dendryTerra)

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
    // -PdendryJar=<path to DendryTerra jar> builds against a local DendryTerra build instead of the
    // published artifact (for testing DendryTerra branches). Default: the published version.
    val localDendry = project.findProperty("dendryJar") as String?
    if(localDendry != null) api(files(localDendry))
    else api("com.github.diytechy", "dendryterra", Versions.Libraries.dendryTerra)
}
