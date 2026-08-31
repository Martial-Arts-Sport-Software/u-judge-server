package org.mass.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

sealed interface PostgresProvisioningState {
    data class Ready(val initialized: Boolean) : PostgresProvisioningState

    data class Failed(val diagnostic: String) : PostgresProvisioningState
}

/** Initializes a local PostgreSQL data directory with the bundled initdb executable. */
class PostgresProvisioner(
    private val initdbCommand: List<String>,
    private val dataDirectory: Path,
    private val timeout: Duration = Duration.ofMinutes(1),
) {
    init {
        require(initdbCommand.isNotEmpty())
    }

    fun initialize(): PostgresProvisioningState {
        if (Files.exists(dataDirectory.resolve("PG_VERSION"))) {
            return PostgresProvisioningState.Ready(initialized = false)
        }
        if (Files.exists(dataDirectory) && !Files.isDirectory(dataDirectory)) {
            return PostgresProvisioningState.Failed("PostgreSQL data path is not a directory: $dataDirectory")
        }
        if (Files.isDirectory(dataDirectory) && Files.newDirectoryStream(dataDirectory).use { it.iterator().hasNext() }) {
            return PostgresProvisioningState.Failed(
                "PostgreSQL data directory is not empty and has no PG_VERSION marker: $dataDirectory",
            )
        }

        return try {
            val process = ProcessBuilder(initdbCommand + listOf("-D", dataDirectory.toString()))
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return PostgresProvisioningState.Failed("PostgreSQL initdb timed out for $dataDirectory")
            }
            if (process.exitValue() != 0) {
                return PostgresProvisioningState.Failed("PostgreSQL initdb exited with code ${process.exitValue()}")
            }
            if (!Files.exists(dataDirectory.resolve("PG_VERSION"))) {
                return PostgresProvisioningState.Failed("PostgreSQL initdb did not create PG_VERSION in $dataDirectory")
            }
            PostgresProvisioningState.Ready(initialized = true)
        } catch (exception: IOException) {
            PostgresProvisioningState.Failed("Unable to initialize PostgreSQL: ${exception.message}")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            PostgresProvisioningState.Failed("PostgreSQL initdb was interrupted")
        }
    }
}
