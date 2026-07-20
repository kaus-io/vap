rootProject.name = "vap"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":vap-core")
include(":vap-decode-api")
include(":vap-decode-android")
include(":vap-decode-jvm")
include(":vap-vk-android")
include(":vap-encode")
include(":vap-compose")
include(":example-vap-android")
include(":example-vap-desktop")
include(":example-vap-shared")
include(":app-vap-tool")
