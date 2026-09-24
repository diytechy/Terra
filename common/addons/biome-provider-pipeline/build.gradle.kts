version = version("2.0.0")

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
}
// Lets `gradlew test -Dterra.pipeline.legacyCacheIndex=true` run the cache tests against the old lookup.
tasks.withType<Test>().configureEach {
    System.getProperty("terra.pipeline.legacyCacheIndex")?.let { systemProperty("terra.pipeline.legacyCacheIndex", it) }
    testLogging { events("passed", "failed") }
}
