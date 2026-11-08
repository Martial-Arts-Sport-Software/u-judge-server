package org.mass.persistence

import org.postgresql.ds.PGSimpleDataSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
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
    val databaseName: String = DEFAULT_DATABASE,
    val databaseUser: String = "postgres",
) {
    val dataDirectory: Path = applicationDataDirectory.resolve("postgres")
    val logFile: Path = applicationDataDirectory.resolve("logs").resolve("postgres.log")
    val pgCtlExecutable: Path = executablePath("pg_ctl")
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
        require(databaseName.matches(IDENTIFIER))
        require(databaseUser.isNotBlank())
        require(!dataDirectory.toAbsolutePath().normalize().startsWith(installationDirectory.toAbsolutePath().normalize())) {
            "PostgreSQL data directory must be outside the installation directory"
        }
    }

    private fun executablePath(name: String): Path = installationDirectory
        .resolve("postgresql")
        .resolve("bin")
        .resolve(platform.executable(name))

    companion object {
        const val DEFAULT_DATABASE = "u_judge"
        val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
    }
}

/** Runs the configured PostgreSQL cluster and only publishes its connection URL after startup. */
class ManagedPostgresRuntime(
    private val configuration: PostgresRuntimeConfiguration,
    private val managedPostgres: ManagedPostgres = ManagedPostgres(
        command = configuration.postgresCommand,
        provisioner = PostgresProvisioner(configuration.initdbCommand, configuration.dataDirectory),
        logFile = configuration.logFile,
    ),
    private val readinessTimeout: Duration = Duration.ofSeconds(30),
    private val orphanStopTimeout: Duration = Duration.ofSeconds(30),
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

    /** A datasource of another database of the running cluster, such as the target of a restore (ADR-012 §7). */
    fun dataSource(databaseName: String): DataSource {
        check(state is PostgresState.Running) { "PostgreSQL is not running" }
        require(databaseName.matches(PostgresRuntimeConfiguration.IDENTIFIER))
        return PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://127.0.0.1:${configuration.port}/$databaseName")
            user = configuration.databaseUser
        }
    }

    /** Creates the empty database [databaseName] in the running cluster; an existing one is an error. */
    fun createDatabase(databaseName: String) {
        require(databaseName.matches(PostgresRuntimeConfiguration.IDENTIFIER))
        DriverManager.getConnection(configuration.administrationJdbcUrl, configuration.databaseUser, null).use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE DATABASE $databaseName") }
        }
    }

    /** Drops [databaseName], which must not be the active database: only a failed restore target is dropped. */
    fun dropDatabase(databaseName: String) {
        require(databaseName.matches(PostgresRuntimeConfiguration.IDENTIFIER))
        require(databaseName != configuration.databaseName) { "The active database is never dropped" }
        DriverManager.getConnection(configuration.administrationJdbcUrl, configuration.databaseUser, null).use { connection ->
            connection.createStatement().use { it.executeUpdate("DROP DATABASE IF EXISTS $databaseName") }
        }
    }

    fun start(): PostgresState {
        if (managedPostgres.state !is PostgresState.Running) {
            stopOrphanedServer()?.let { return managedPostgres.fail(it) }
        }
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

    /**
     * On Windows `Process.destroy()` terminates the postmaster without a shutdown, so every start would run crash recovery
     * (seen on the Windows installer smoke test, I7). There the cluster is first shut down through `pg_ctl` in fast mode;
     * elsewhere `destroy()` sends SIGTERM, which PostgreSQL already handles as a clean shutdown.
     */
    fun stop(): PostgresState {
        if (configuration.platform == PostgresPlatform.Windows && managedPostgres.state is PostgresState.Running) {
            runCatching {
                val process = ProcessBuilder(
                    configuration.pgCtlExecutable.toString(), "stop",
                    "-D", configuration.dataDirectory.toString(),
                    "-m", "fast", "-w", "-t", orphanStopTimeout.seconds.toString(),
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                if (!process.waitFor(orphanStopTimeout.toMillis() + 5_000, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
        return managedPostgres.stop()
    }

    fun restart(): PostgresState {
        stop()
        return start()
    }

    /**
     * A forced exit of the application (for example `kill -9`) leaves its PostgreSQL child running and holding the cluster.
     * Stops such a server through `pg_ctl` in fast mode, which lets PostgreSQL finish crash-safe shutdown; a stale lock file
     * of a dead process is left to PostgreSQL itself. Returns a diagnostic when the cluster cannot be released.
     */
    private fun stopOrphanedServer(): String? {
        val lockFile = configuration.dataDirectory.resolve("postmaster.pid")
        if (!Files.exists(lockFile)) return null
        val pid = Files.readAllLines(lockFile).firstOrNull()?.trim()?.toLongOrNull() ?: return null
        val orphan = ProcessHandle.of(pid).orElse(null) ?: return null
        val command = orphan.info().command().orElse("")
        if (!orphan.isAlive || !command.contains("postgres")) return null

        return try {
            val process = ProcessBuilder(
                configuration.pgCtlExecutable.toString(), "stop",
                "-D", configuration.dataDirectory.toString(),
                "-m", "fast", "-w", "-t", orphanStopTimeout.seconds.toString(),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(orphanStopTimeout.toMillis() + 5_000, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                "PostgreSQL left running by a previous run (PID $pid) did not stop in time"
            } else if (process.exitValue() != 0 && orphan.isAlive) {
                "PostgreSQL left running by a previous run (PID $pid) could not be stopped"
            } else {
                null
            }
        } catch (exception: IOException) {
            "Unable to stop PostgreSQL left running by a previous run: ${exception.message}"
        }
    }

    private fun waitUntilReady() {
        val deadline = Instant.now().plus(readinessTimeout)
        var lastFailure: SQLException? = null
        while (Instant.now().isBefore(deadline)) {
            // A child that already exited will never accept connections: report why now instead of after the timeout.
            (managedPostgres.state as? PostgresState.Failed)?.let { throw SQLException(exitDiagnostic(it.diagnostic)) }
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

    /**
     * PostgreSQL refuses to start in an elevated Windows session (UAC off or «Run as administrator»), which a clean CI
     * runner reproduces (I7); the operator gets the remedy instead of the bare exit code.
     */
    private fun exitDiagnostic(exit: String): String {
        val log = runCatching { Files.readString(configuration.logFile).takeLast(4_096) }.getOrDefault("")
        return if (log.contains("administrative permissions")) ADMINISTRATOR_DIAGNOSTIC else exit
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

/** The remedy shown when PostgreSQL refused to run with administrator rights. */
const val ADMINISTRATOR_DIAGNOSTIC = "PostgreSQL does not run with administrator rights: start U'Judge as a standard Windows " +
    "user, not with «Run as administrator» or with UAC turned off"
