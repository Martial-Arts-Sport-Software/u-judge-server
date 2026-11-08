package org.mass

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The headless `--smoke-test` of an installed application against the bundled PostgreSQL (installer workflow, I7). */
class ServerSmokeCheckTest {
    private val root = createTempDirectory()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `parses the smoke test options and ignores other arguments`() {
        assertNull(ServerSmokeCheck.options(arrayOf("--port=9000")))
        assertEquals(
            ServerSmokeCheck.Options(Path.of("/tmp/data"), 9000, Path.of("report.txt")),
            ServerSmokeCheck.options(arrayOf("--smoke-test", "--data-directory=/tmp/data", "--port=9000", "--report=report.txt")),
        )
        assertEquals(ServerSmokeCheck.Options(null, null, null), ServerSmokeCheck.options(arrayOf("--smoke-test")))
    }

    @Test
    fun `starts the server with PostgreSQL, answers health over pinned TLS, stops and keeps the peer on a second run`() {
        val installation = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(!installation.isNullOrBlank(), "Requires the PostgreSQL bundle prepared by Gradle")
        val environment = ServerRuntimeConfiguration(Path.of(installation), root.resolve("unused"))
        val data = root.resolve("application-data")

        val first = report(environment, data, "first.txt")
        assertEquals("passed", first["result"], first.toString())
        assertEquals("healthy", first["health"])
        assertEquals(first["peerId"], first["metadataPeerId"])
        assertEquals("true", first["postgresStopped"])

        val second = report(environment, data, "second.txt")
        assertEquals("passed", second["result"], second.toString())
        assertEquals(first["peerId"], second["peerId"])
    }

    @Test
    fun `fails with a diagnostic when the PostgreSQL bundle is missing`() {
        val environment = ServerRuntimeConfiguration(root.resolve("no-bundle"), root.resolve("unused"))
        val failed = report(environment, root.resolve("application-data"), "failed.txt", expectedExit = 1)
        assertEquals("failed", failed["result"])
        assertEquals("failed", failed["runtime"])
    }

    private fun report(environment: ServerRuntimeConfiguration, data: Path, name: String, expectedExit: Int = 0): Map<String, String> {
        val file = root.resolve(name)
        val options = ServerSmokeCheck.Options(data, ServerSocket(0).use { it.localPort }, file)
        assertEquals(expectedExit, ServerSmokeCheck.run(options, environment))
        return Files.readAllLines(file).associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
