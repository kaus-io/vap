import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka)
}

val publishableModules = listOf(
    "vap-core",
    "vap-decode",
    "vap-encode",
    "vap-compose",
)

tasks.register("printPublishableModules") {
    group = "help"
    description = "Prints publishable module names (one per line) for CI scripts."
    val names = publishableModules
    doLast {
        names.sorted().forEach { println(it) }
    }
}

configure(subprojects.filter { it.name in publishableModules.toSet() }) {
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        // Signing only when credentials exist; local publishToMavenLocal works without.
        // signAllPublications()

        coordinates(group.toString(), project.name, version.toString())

        pom {
            name = project.name
            description = "Kotlin Multiplatform VAP (alpha video) player libraries."
            inceptionYear = "2026"
            url = "https://github.com/zxhhyj/vap"
            licenses {
                license {
                    name = "MIT"
                    url = "https://opensource.org/licenses/MIT"
                    distribution = "https://opensource.org/licenses/MIT"
                }
            }
            developers {
                developer {
                    id = "zxhhyj"
                    name = "zxhhyj"
                }
            }
            scm {
                url = "https://github.com/zxhhyj/vap"
            }
        }
    }
}
