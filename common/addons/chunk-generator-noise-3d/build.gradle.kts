version = version("1.2.1")

dependencies {
    compileOnlyApi(project(":common:addons:manifest-addon-loader"))
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed") }
}
