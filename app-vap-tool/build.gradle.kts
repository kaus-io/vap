import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.vapEncode)
    implementation(projects.vapCompose)

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.material)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.filekit.dialogs)
    implementation(libs.filekit.dialogs.compose)
}

compose.resources {
    packageOfResClass = "com.zxhhyj.vap.tool.generated.resources"
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "com.zxhhyj.vap.tool.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "vap-tool"
            packageVersion = "0.1.0"
            linux {
                modules("jdk.security.auth")
            }
        }
    }
}
