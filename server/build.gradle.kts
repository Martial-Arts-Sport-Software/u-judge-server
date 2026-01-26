plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":desktop"))
}

application {
    mainClass.set("org.mass.u_judge_server.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}