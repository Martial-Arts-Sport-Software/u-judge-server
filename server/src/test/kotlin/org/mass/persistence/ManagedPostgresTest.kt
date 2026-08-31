package org.mass.persistence

import java.net.ServerSocket
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedPostgresTest {
    @Test
    fun `starts configured process and stops only supervised child`() {
        val postgres = ManagedPostgres(waitingCommand(port = 54321))

        val running = postgres.start()

        assertIs<PostgresState.Running>(running)
        assertTrue(running.process.isAlive)
        assertEquals(PostgresState.Stopped, postgres.stop())
        assertEquals(PostgresState.Stopped, postgres.state)
        assertTrue(!running.process.isAlive)
    }

    @Test
    fun `reports occupied configured port without starting process`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val postgres = ManagedPostgres(waitingCommand(port = socket.localPort))

            val state = postgres.start()

            val failed = assertIs<PostgresState.Failed>(state)
            assertTrue(failed.diagnostic.contains(socket.localPort.toString()))
        }
    }

    @Test
    fun `reports process startup failure`() {
        val postgres = ManagedPostgres(PostgresCommand(listOf("/missing/postgres"), port = 54322))

        val state = postgres.start()

        val failed = assertIs<PostgresState.Failed>(state)
        assertTrue(failed.diagnostic.contains("Unable to start PostgreSQL"))
    }

    @Test
    fun `reports unexpected child exit`() {
        val postgres = ManagedPostgres(javaCommand("-version", port = 54323))

        val running = assertIs<PostgresState.Running>(postgres.start())
        assertTrue(running.process.waitFor(5, TimeUnit.SECONDS))

        val failed = assertIs<PostgresState.Failed>(postgres.state)
        assertTrue(failed.diagnostic.contains("exited"))
    }

    @Test
    fun `initializes the data directory before starting the supervised child`() {
        val root = createTempDirectory()
        try {
            val dataDirectory = root.resolve("postgres")
            val provisioner = PostgresProvisioner(
                initdbCommand(
                    "-cp",
                    System.getProperty("java.class.path"),
                    InitdbProcessFixture::class.java.name,
                    "",
                    dataDirectory.toString(),
                ),
                dataDirectory,
            )
            val postgres = ManagedPostgres(waitingCommand(port = 54324), provisioner = provisioner)

            val running = assertIs<PostgresState.Running>(postgres.start())

            assertTrue(Files.exists(dataDirectory.resolve("PG_VERSION")))
            assertEquals(PostgresState.Stopped, postgres.stop())
            assertTrue(!running.process.isAlive)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun waitingCommand(port: Int): PostgresCommand = javaCommand(
        "-cp",
        System.getProperty("java.class.path"),
        ManagedPostgresProcessFixture::class.java.name,
        port = port,
    )

    private fun javaCommand(vararg arguments: String, port: Int): PostgresCommand = PostgresCommand(
        arguments = listOf(ProcessHandle.current().info().command().orElseThrow()) + arguments,
        port = port,
    )

    private fun initdbCommand(vararg arguments: String): List<String> =
        listOf(ProcessHandle.current().info().command().orElseThrow()) + arguments
}

object ManagedPostgresProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(60_000)
    }
}
