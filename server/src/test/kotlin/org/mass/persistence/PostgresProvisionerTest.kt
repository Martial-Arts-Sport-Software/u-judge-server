package org.mass.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostgresProvisionerTest {
    @Test
    fun `initializes a missing data directory with initdb`() {
        val root = createTempDirectory()
        try {
            val dataDirectory = root.resolve("postgres")
            val provisioner = PostgresProvisioner(initdbCommand(dataDirectory), dataDirectory)

            val state = provisioner.initialize()

            val ready = assertIs<PostgresProvisioningState.Ready>(state)
            assertTrue(ready.initialized)
            assertTrue(Files.exists(dataDirectory.resolve("PG_VERSION")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reuses an initialized data directory without running initdb again`() {
        val root = createTempDirectory()
        try {
            val dataDirectory = root.resolve("postgres")
            val invocationMarker = root.resolve("initdb-runs")
            val provisioner = PostgresProvisioner(initdbCommand(dataDirectory, invocationMarker), dataDirectory)

            assertIs<PostgresProvisioningState.Ready>(provisioner.initialize())
            val state = assertIs<PostgresProvisioningState.Ready>(provisioner.initialize())

            assertEquals(false, state.initialized)
            assertEquals("1", Files.readString(invocationMarker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not overwrite a nonempty directory without a PostgreSQL marker`() {
        val root = createTempDirectory()
        try {
            val dataDirectory = root.resolve("postgres")
            Files.createDirectory(dataDirectory)
            val existingFile = dataDirectory.resolve("existing-data")
            Files.writeString(existingFile, "preserve")
            val provisioner = PostgresProvisioner(initdbCommand(dataDirectory), dataDirectory)

            val state = provisioner.initialize()

            val failed = assertIs<PostgresProvisioningState.Failed>(state)
            assertTrue(failed.diagnostic.contains("not empty"))
            assertEquals("preserve", Files.readString(existingFile))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports an actionable diagnostic when initdb cannot start`() {
        val root = createTempDirectory()
        try {
            val state = PostgresProvisioner(listOf("/missing/initdb"), root.resolve("postgres")).initialize()

            val failed = assertIs<PostgresProvisioningState.Failed>(state)
            assertTrue(failed.diagnostic.contains("Unable to initialize PostgreSQL"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun initdbCommand(dataDirectory: Path, invocationMarker: Path? = null): List<String> = javaCommand(
        "-cp",
        System.getProperty("java.class.path"),
        InitdbProcessFixture::class.java.name,
        invocationMarker?.toString() ?: "",
        dataDirectory.toString(),
    )

    private fun javaCommand(vararg arguments: String): List<String> =
        listOf(ProcessHandle.current().info().command().orElseThrow()) + arguments
}

object InitdbProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val marker = args[0].takeIf { it.isNotEmpty() }?.let(Path::of)
        val dataDirectory = args[1].let(Path::of)
        Files.createDirectories(dataDirectory)
        Files.writeString(dataDirectory.resolve("PG_VERSION"), "16")
        marker?.let { Files.writeString(it, "1") }
    }
}
