plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(libs.kotlinx.coroutinesSwing)

    // Postgres & ORM
    implementation(libs.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
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

application {
    mainClass.set("org.mass.u_judge_server.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}