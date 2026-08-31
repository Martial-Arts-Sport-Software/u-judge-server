package org.mass.persistence

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit

data class PostgresCommand(
    val arguments: List<String>,
    val port: Int,
) {
    init {
        require(arguments.isNotEmpty())
        require(port in 1..65535)
    }
}

sealed interface PostgresState {
    data object Stopped : PostgresState

    data class Running(val process: Process) : PostgresState

    data class Failed(val diagnostic: String) : PostgresState
}

class ManagedPostgres(
    private val command: PostgresCommand,
    private val provisioner: PostgresProvisioner? = null,
    private val stopTimeout: Duration = Duration.ofSeconds(5),
) {
    private var currentState: PostgresState = PostgresState.Stopped

    val state: PostgresState
        get() = refreshState()

    fun start(): PostgresState {
        val current = refreshState()
        if (current is PostgresState.Running) return current

        val provisioning = provisioner?.initialize()
        if (provisioning is PostgresProvisioningState.Failed) {
            return PostgresState.Failed(provisioning.diagnostic).also { currentState = it }
        }

        if (!isPortAvailable(command.port)) {
            return PostgresState.Failed("PostgreSQL port ${command.port} is already in use").also {
                currentState = it
            }
        }

        return try {
            PostgresState.Running(ProcessBuilder(command.arguments).start()).also { currentState = it }
        } catch (exception: IOException) {
            PostgresState.Failed("Unable to start PostgreSQL: ${exception.message}").also { currentState = it }
        }
    }

    fun stop(): PostgresState {
        val running = currentState as? PostgresState.Running ?: return PostgresState.Stopped.also { currentState = it }
        running.process.destroy()
        if (!running.process.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            running.process.destroyForcibly()
            running.process.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)
        }
        return PostgresState.Stopped.also { currentState = it }
    }

    private fun refreshState(): PostgresState {
        val running = currentState as? PostgresState.Running ?: return currentState
        if (running.process.isAlive) return running

        return PostgresState.Failed("PostgreSQL process exited with code ${running.process.exitValue()}").also {
            currentState = it
        }
    }

    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true }
    } catch (_: IOException) {
        false
    }
}
