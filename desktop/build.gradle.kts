import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvmToolchain(21)
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
    implementation(libs.navigation.compose)

    implementation(compose.desktop.currentOs)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.dns.sd.kt)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    implementation(project(":server"))
}

/** Places the PostgreSQL bundle prepared by `:server` into the app resources shipped with the desktop application. */
val preparePostgresResources = tasks.register<Sync>("preparePostgresResources") {
    from(project(":server").tasks.named("preparePostgresBundle"))
    into(layout.buildDirectory.dir("app-resources/common"))
}

compose.desktop {
    application {
        mainClass = "org.mass.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(preparePostgresResources.map { layout.buildDirectory.dir("app-resources").get() })
            // From `:desktop:suggestRuntimeModules`; java.sql carries the JDBC API used by the PostgreSQL driver.
            modules("java.instrument", "java.management", "java.naming", "java.security.jgss", "java.sql", "jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UJudgeServer"
            packageVersion = "1.0.0"
        }
    }
}

afterEvaluate {
    tasks.named<JavaExec>("run") {
        standardInput = System.`in`
        // Appends to the Compose arguments, which carry `compose.application.resources.dir` with the PostgreSQL bundle.
        jvmArgs(
            listOfNotNull(
                "-Duser.timezone=UTC",
                // `-PuJudgeDataDirectory=/path` runs against a separate cluster instead of the OS application-data directory.
                providers.gradleProperty("uJudgeDataDirectory").orNull?.let { "-DuJudge.dataDirectory=$it" },
            ),
        )
    }
}
