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
                implementation(kotlin("stdlib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
                implementation("io.github.oshai:kotlin-logging:5.1.0")
                implementation("com.benasher44:uuid:0.8.0")
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                // Ktor Server
                implementation("io.ktor:ktor-server-core:2.3.5")
                implementation("io.ktor:ktor-server-cio:2.3.5")
                implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")

                // Ktor Client
                implementation("io.ktor:ktor-client-core:2.3.5")
                implementation("io.ktor:ktor-client-cio:2.3.5")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.5")

                // PostgreSQL & ORM
                implementation("org.postgresql:postgresql:42.7.0")
                implementation("org.jetbrains.exposed:exposed-core:0.41.1")
                implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
                implementation("org.jetbrains.exposed:exposed-java-time:0.41.1")

                // Connection Pool
                implementation("com.zaxxer:HikariCP:5.1.0")

                // Logging
                implementation("ch.qos.logback:logback-classic:1.4.11")
                implementation("org.slf4j:slf4j-api:2.0.9")

                // Lifecycle & Coroutines (JVM only)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
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
        classpath = sourceSets["main"].runtimeClasspath  // ← правильно для KMP!
        mainClass.set("org.mass.u_judge_server.MainKt")
        standardInput = System.`in`
        jvmArgs = listOf("-Duser.timezone=UTC")
    }
}

