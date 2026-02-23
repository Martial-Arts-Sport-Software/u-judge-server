import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.logging)
    implementation(libs.uuid)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.components.uiToolingPreview)

    implementation(compose.desktop.currentOs)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    implementation(project(":server"))
}

compose.desktop {
    application {
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UJudgeServer"
            packageVersion = "1.0.0"
        }
    }
}

afterEvaluate {
    tasks.named<JavaExec>("run") {
        standardInput = System.`in`
        jvmArgs = listOf("-Duser.timezone=UTC")
    }
}