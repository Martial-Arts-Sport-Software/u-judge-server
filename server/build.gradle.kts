plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.h2)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)

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
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Connection Pool
    implementation(libs.hikari)
    implementation(libs.dns.sd.kt)
}

val postgresBinaries = configurations.create("postgresBinaries") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    postgresBinaries(
        "io.zonky.test.postgres:embedded-postgres-binaries-${postgresBinariesPlatform()}:" +
            libs.versions.postgres.binaries.get(),
    )
}

/** Extracts the pinned PostgreSQL binaries into `<bundle>/postgresql/{bin,lib,share}` for the current OS. */
abstract class PreparePostgresBundle @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val binaries: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val bundleDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        files.delete { delete(bundleDirectory) }
        val target = bundleDirectory.dir("postgresql").get().asFile.apply { mkdirs() }
        val archive = archives.zipTree(binaries.singleFile).matching { include("*.txz") }.singleFile
        exec.exec { commandLine("tar", "-xJf", archive.absolutePath, "-C", target.absolutePath) }
    }
}

val preparePostgresBundle = tasks.register<PreparePostgresBundle>("preparePostgresBundle") {
    binaries.from(postgresBinaries)
    bundleDirectory.set(layout.buildDirectory.dir("postgres-bundle"))
}

tasks.test {
    useJUnitPlatform()
    val bundle = preparePostgresBundle.flatMap(PreparePostgresBundle::bundleDirectory)
    inputs.dir(bundle).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("postgresBundle")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-DuJudge.postgres.installationDirectory=${bundle.get().asFile.absolutePath}")
        },
    )
}

fun postgresBinariesPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    return when {
        os.startsWith("mac") -> if (arm) "darwin-arm64v8" else "darwin-amd64"
        os.startsWith("windows") -> "windows-amd64"
        else -> if (arm) "linux-arm64v8" else "linux-amd64"
    }
}

application {
    mainClass.set("org.mass.ServerKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    val bundle = preparePostgresBundle.flatMap(PreparePostgresBundle::bundleDirectory)
    inputs.dir(bundle).withPropertyName("postgresBundle")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-DuJudge.postgres.installationDirectory=${bundle.get().asFile.absolutePath}")
        },
    )
}
