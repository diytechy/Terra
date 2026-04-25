import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.provideDelegate

fun Project.configurePublishing() {
    // Guard publishToMavenLocal so it only runs on a clean tree.
    // Terra versions include the git commit hash; publishing with uncommitted changes
    // would record the previous HEAD hash, which becomes stale once those changes are
    // committed and the hash changes. Committing first ensures the published artifact
    // and its version string refer to the same committed state.
    afterEvaluate {
        tasks.matching { it.name == "publishToMavenLocal" }.configureEach {
            doFirst {
                val dirty = providers.exec {
                    commandLine("git", "status", "--porcelain")
                }.standardOutput.asText.get().trim()
                if (dirty.isNotEmpty()) {
                    throw GradleException(
                        "Compilation successful.\n" +
                        "publishToMavenLocal blocked: working tree has uncommitted changes.\n" +
                        "Commit your changes and re-run publishToMavenLocal so the published\n" +
                        "version hash matches the committed state.\n" +
                        "Dirty files:\n$dirty"
                    )
                }
            }
        }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
            }
        }

        repositories {
            val mavenUrl = "https://maven.solo-studios.ca/releases/"
            //val mavenSnapshotUrl = "https://repo.codemc.io/repository/maven-snapshots/"

            maven(mavenUrl) {
                val SoloStudiosReleasesUsername: String? by project
                val SoloStudiosReleasesPassword: String? by project

                if (SoloStudiosReleasesUsername != null && SoloStudiosReleasesPassword != null) {
                    credentials {
                        username = SoloStudiosReleasesUsername
                        password = SoloStudiosReleasesPassword
                    }
                }
            }

            maven("https://repo.repsy.io/mvn/diytechy/terra") {
                name = "Repsy"
                credentials {
                    username = project.findProperty("repsy.user") as String?
                        ?: System.getenv("REPSY_USERNAME") ?: ""
                    password = project.findProperty("repsy.key") as String?
                        ?: System.getenv("REPSY_PASSWORD") ?: ""
                }
            }
        }
    }
}