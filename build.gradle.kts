import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
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
    "vap-decode-api",
    "vap-decode-android",
    "vap-decode-jvm",
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

dependencies {
    publishableModules.forEach { dokka(project(":$it")) }
}

dokka {
    moduleName.set("VAP")
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

configure(subprojects.filter { it.name in publishableModules.toSet() }) {
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        // Local A/B: publishToMavenLocal -Pversion=1.0.4-LOCAL -PskipSigning
        val skipSigning = providers.gradleProperty("skipSigning")
            .map { it == "true" || it == "1" }
            .orElse(false)
        if (!skipSigning.get()) {
            signAllPublications()
        }

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
