plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.h2)

    implementation(libs.kotlinx.coroutinesSwing)

    // Postgres & ORM
    implementation(libs.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Connection Pool
    implementation(libs.hikari)
    implementation(libs.dns.sd.kt)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.mass.ServerKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
