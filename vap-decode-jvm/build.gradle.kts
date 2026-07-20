import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        jvmMain.dependencies {
            api(projects.vapDecodeApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.bytedeco.ffmpeg.platform)
            implementation(libs.skiko.awt)
        }
    }
}
