package org.mass.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mass.replication.JdbcPeerJournal
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealPostgresLifecycleTest {
    @Test
    fun `initializes migrates and recovers the journal after a real PostgreSQL restart`() {
        val installationDirectory = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(
            !installationDirectory.isNullOrBlank(),
            "Set -DuJudge.postgres.installationDirectory to a PostgreSQL bundle root to run this acceptance test",
        )
        val root = createTempDirectory()
        val configuration = PostgresRuntimeConfiguration(
            installationDirectory = Path.of(installationDirectory),
            applicationDataDirectory = root.resolve("application-data"),
            port = PostgresCommand.withAvailableLoopbackPort(listOf("postgres")).port,
            platform = if (System.getProperty("os.name").startsWith("Windows")) PostgresPlatform.Windows else PostgresPlatform.MacOs,
        )
        val runtime = ManagedPostgresRuntime(configuration)

        try {
            assertIs<PostgresState.Running>(runtime.start())
            val firstProcess = JdbcPeerJournal("court-1", requireNotNull(runtime.dataSource))
            val firstEvent = firstProcess.append("first-score")

            assertEquals(PostgresState.Stopped, runtime.stop())
            assertIs<PostgresState.Running>(runtime.start())
            val restartedProcess = JdbcPeerJournal("court-1", requireNotNull(runtime.dataSource))
            val secondEvent = restartedProcess.append("second-score")

            assertEquals(listOf(firstEvent, secondEvent), restartedProcess.events)
            assertEquals(listOf("first-score", "second-score"), restartedProcess.projectedPayloads)
        } finally {
            runtime.stop()
            root.toFile().deleteRecursively()
        }
    }
}
