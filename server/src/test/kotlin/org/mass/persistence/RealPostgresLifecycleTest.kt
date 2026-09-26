package org.mass.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mass.replication.JdbcPeerJournal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
            platform = PostgresPlatform.current(),
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

    @Test
    fun `upgrades the legacy pilot schema into the unified domain journal on real PostgreSQL`() {
        val installationDirectory = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(
            !installationDirectory.isNullOrBlank(),
            "Set -DuJudge.postgres.installationDirectory to a PostgreSQL bundle root to run this acceptance test",
        )
        val root = createTempDirectory()
        val runtime = ManagedPostgresRuntime(
            PostgresRuntimeConfiguration(
                installationDirectory = Path.of(installationDirectory),
                applicationDataDirectory = root.resolve("application-data"),
                port = PostgresCommand.withAvailableLoopbackPort(listOf("postgres")).port,
                platform = PostgresPlatform.current(),
            ),
        )

        try {
            assertIs<PostgresState.Running>(runtime.start())
            DomainJournalFixture.verifyLegacyUpgrade(requireNotNull(runtime.dataSource))
        } finally {
            runtime.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recovers the cluster and journal after the application was killed with its PostgreSQL child running`() {
        val installationDirectory = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(
            !installationDirectory.isNullOrBlank(),
            "Set -DuJudge.postgres.installationDirectory to a PostgreSQL bundle root to run this acceptance test",
        )
        val root = createTempDirectory()
        fun configuration() = PostgresRuntimeConfiguration(
            installationDirectory = Path.of(installationDirectory),
            applicationDataDirectory = root.resolve("application-data"),
            port = PostgresCommand.withAvailableLoopbackPort(listOf("postgres")).port,
            platform = PostgresPlatform.current(),
        )
        val killedRun = ManagedPostgresRuntime(configuration())
        val orphan = assertIs<PostgresState.Running>(killedRun.start()).process
        val restartedRun = ManagedPostgresRuntime(configuration())

        try {
            val event = JdbcPeerJournal("court-1", requireNotNull(killedRun.dataSource)).append("before-kill")

            // The killed application never calls stop(); a new run with a new port must take the cluster over.
            assertIs<PostgresState.Running>(restartedRun.start())
            assertEquals(false, orphan.isAlive)
            assertEquals(listOf(event), JdbcPeerJournal("court-1", requireNotNull(restartedRun.dataSource)).events)
            assertTrue(Files.size(root.resolve("application-data/logs/postgres.log")) > 0)
        } finally {
            restartedRun.stop()
            orphan.destroyForcibly()
            root.toFile().deleteRecursively()
        }
    }
}
