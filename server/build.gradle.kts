plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":shared"))
}

application {
    mainClass.set("org.mass.u_judge_server.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}