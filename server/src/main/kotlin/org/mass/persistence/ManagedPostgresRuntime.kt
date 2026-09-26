package org.mass.persistence

import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

enum class PostgresPlatform(private val executableSuffix: String) {
    Windows(".exe"),
    MacOs(""),

    /** Only for CI acceptance runs; the pilot desktop targets Windows and macOS. */
    Linux(""),
    ;

    fun executable(name: String): String = "$name$executableSuffix"

    companion object {
        fun current(osName: String = System.getProperty("os.name")): PostgresPlatform = when {
            osName.startsWith("Windows") -> Windows
            osName.startsWith("Mac") -> MacOs
            else -> Linux
        }
    }
}

/** Defines one bundled PostgreSQL cluster and the commands required to run it. */
data class PostgresRuntimeConfiguration(
    val installationDirectory: Path,
    val applicationDataDirectory: Path,
    val port: Int,
    val platform: PostgresPlatform,
    val databaseName: String = "u_judge",
    val databaseUser: String = "postgres",
) {
    val dataDirectory: Path = applicationDataDirectory.resolve("postgres")
    val initdbCommand: List<String> = listOf(
        executablePath("initdb").toString(),
        "--username",
        databaseUser,
    )
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
    val administrationJdbcUrl: String = "jdbc:postgresql://127.0.0.1:$port/postgres"

    init {
        require(databaseName.isNotBlank())
        require(databaseName.matches(POSTGRES_IDENTIFIER))
        require(databaseUser.isNotBlank())
        require(!dataDirectory.toAbsolutePath().normalize().startsWith(installationDirectory.toAbsolutePath().normalize())) {
            "PostgreSQL data directory must be outside the installation directory"
        }
    }

    private fun executablePath(name: String): Path = installationDirectory
        .resolve("postgresql")
        .resolve("bin")
        .resolve(platform.executable(name))

    private companion object {
        val POSTGRES_IDENTIFIER = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    }
}

/** Runs the configured PostgreSQL cluster and only publishes its connection URL after startup. */
class ManagedPostgresRuntime(
    private val configuration: PostgresRuntimeConfiguration,
    private val managedPostgres: ManagedPostgres = ManagedPostgres(
        command = configuration.postgresCommand,
        provisioner = PostgresProvisioner(configuration.initdbCommand, configuration.dataDirectory),
    ),
    private val readinessTimeout: Duration = Duration.ofSeconds(30),
) {
    val state: PostgresState
        get() = managedPostgres.state

    val jdbcUrl: String?
        get() = configuration.jdbcUrl.takeIf { state is PostgresState.Running }

    val dataSource: DataSource?
        get() = jdbcUrl?.let {
            PGSimpleDataSource().apply {
                setURL(it)
                user = configuration.databaseUser
            }
        }

    fun start(): PostgresState {
        val started = managedPostgres.start()
        if (started !is PostgresState.Running) return started

        return try {
            waitUntilReady()
            createDatabaseIfMissing()
            started
        } catch (exception: SQLException) {
            managedPostgres.fail("PostgreSQL did not become ready: ${exception.message}")
        }
    }

    fun stop(): PostgresState = managedPostgres.stop()

    fun restart(): PostgresState {
        stop()
        return start()
    }

    private fun waitUntilReady() {
        val deadline = Instant.now().plus(readinessTimeout)
        var lastFailure: SQLException? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                DriverManager.getConnection(configuration.administrationJdbcUrl, configuration.databaseUser, null).use {
                    return
                }
            } catch (exception: SQLException) {
                lastFailure = exception
                Thread.sleep(100)
            }
        }
        throw SQLException("Timed out after $readinessTimeout waiting for PostgreSQL", lastFailure)
    }

    private fun createDatabaseIfMissing() {
        DriverManager.getConnection(configuration.administrationJdbcUrl, configuration.databaseUser, null).use { connection ->
            if (databaseExists(connection)) return
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE DATABASE ${configuration.databaseName}")
            }
        }
    }

    private fun databaseExists(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT 1 FROM pg_database WHERE datname = ?",
    ).use { statement ->
        statement.setString(1, configuration.databaseName)
        statement.executeQuery().use { it.next() }
    }
}
