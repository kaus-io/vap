import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()

    android {
        namespace = "com.zxhhyj.vap.decode.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs.add("-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi")
        }
    }

    sourceSets {
        androidMain.dependencies {
            api(projects.vapDecodeApi)
            implementation(projects.vapVkAndroid)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}
