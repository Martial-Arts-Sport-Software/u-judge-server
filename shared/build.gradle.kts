import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.internal.utils.registerOrConfigure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlin.logging)
                implementation(libs.uuid)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                // Ktor Server
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                // Ktor Client
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)

                // PostgreSQL & ORM
                implementation(libs.postgresql)
                implementation(libs.exposed.core)
                implementation(libs.exposed.dao)
                implementation(libs.exposed.java.time)

                // Connection Pool
                implementation(libs.hikari)

                // Logging
                implementation(libs.logback.classic)
                implementation(libs.slf4j.api)

                // Lifecycle & Coroutines (JVM only)
                implementation(libs.kotlinx.coroutinesSwing)
            }
        }
    }
}


compose.desktop {
    application {
        mainClass = "org.mass.u_judge_server.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UJudgeServer"
            packageVersion = "1.0.0"
        }
    }
}

afterEvaluate {

    tasks.registerOrConfigure<JavaExec>("jvmRun") {
        group = "run"
        description = "UJudgeServer JVM Run"
        classpath = kotlin.targets["jvm"].compilations["main"].runtimeDependencyFiles
        mainClass.set("org.mass.u_judge_server.MainKt")
        standardInput = System.`in`
        jvmArgs = listOf("-Duser.timezone=UTC")
    }
}

