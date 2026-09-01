package org.mass.persistence

import java.nio.file.Path

enum class PostgresPlatform(private val executableSuffix: String) {
    Windows(".exe"),
    MacOs(""),
    ;

    fun executable(name: String): String = "$name$executableSuffix"
}

/** Defines one bundled PostgreSQL cluster and the commands required to run it. */
data class PostgresRuntimeConfiguration(
    val installationDirectory: Path,
    val applicationDataDirectory: Path,
    val port: Int,
    val platform: PostgresPlatform,
    val databaseName: String = "u_judge",
) {
    val dataDirectory: Path = applicationDataDirectory.resolve("postgres")
    val initdbCommand: List<String> = listOf(executablePath("initdb").toString(), "-D", dataDirectory.toString())
    val postgresCommand: PostgresCommand = PostgresCommand(
        arguments = listOf(
            executablePath("postgres").toString(),
            "-D",
            dataDirectory.toString(),
            "-h",
            "127.0.0.1",
            "-p",
            port.toString(),
        ),
        port = port,
    )
    val jdbcUrl: String = "jdbc:postgresql://127.0.0.1:$port/$databaseName"

    init {
        require(databaseName.isNotBlank())
        require(!dataDirectory.toAbsolutePath().normalize().startsWith(installationDirectory.toAbsolutePath().normalize())) {
            "PostgreSQL data directory must be outside the installation directory"
        }
    }

    private fun executablePath(name: String): Path = installationDirectory
        .resolve("postgresql")
        .resolve("bin")
        .resolve(platform.executable(name))
}

/** Runs the configured PostgreSQL cluster and only publishes its connection URL after startup. */
class ManagedPostgresRuntime(
    private val configuration: PostgresRuntimeConfiguration,
    private val managedPostgres: ManagedPostgres = ManagedPostgres(
        command = configuration.postgresCommand,
        provisioner = PostgresProvisioner(configuration.initdbCommand, configuration.dataDirectory),
    ),
) {
    val state: PostgresState
        get() = managedPostgres.state

    val jdbcUrl: String?
        get() = configuration.jdbcUrl.takeIf { state is PostgresState.Running }

    fun start(): PostgresState = managedPostgres.start()

    fun stop(): PostgresState = managedPostgres.stop()

    fun restart(): PostgresState = managedPostgres.restart()
}
