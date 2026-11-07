package org.mass.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.ImportOutcome
import org.mass.applications.ImportPreparation
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.applications.SyntheticApplications
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionDrawService
import org.mass.draw.DrawOutcome
import org.mass.draw.JdbcCompetitionDrawJournal
import org.mass.replication.JdbcPeerJournal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
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

    @Test
    fun `imports and draws 500 participants atomically and restores them after a PostgreSQL restart`() {
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
        fun services(): Pair<CompetitionApplicationsService, CompetitionDrawService> {
            val dataSource = requireNotNull(runtime.dataSource)
            val store = JdbcDomainEventStore(dataSource)
            val peerId = LocalPeerIdentity.loadOrCreate(dataSource)
            val applications = CompetitionApplicationsService(
                journal = JdbcCompetitionApplicationsJournal(store, peerId),
                backup = { CompetitionApplicationsService.backupPath(root.resolve("backups"), Instant.now()).also(store::exportJournal) },
            )
            return applications to CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId)
        }

        try {
            assertIs<PostgresState.Running>(runtime.start())
            val (service, draws) = services()
            val preparation = service.prepare("Синтетика", LocalDate.of(2025, 11, 15), SyntheticApplications.files(participants = 500))
            val applications = assertIs<ImportPreparation.Valid>(preparation).applications
            val imported = assertIs<ImportOutcome.Imported>(service.import(applications, replaceConfirmed = false))
            assertTrue(Files.exists(requireNotNull(imported.backup)))
            // Every category is confirmed in one transaction.
            assertEquals(DrawOutcome.Done, draws.drawAll())
            assertEquals(DrawOutcome.Done, draws.confirm())
            val drawn = draws.board()
            assertTrue(drawn.categories.none { it.status == CategoryStatus.NOT_DRAWN || it.status == CategoryStatus.PREVIEW })

            assertEquals(PostgresState.Stopped, runtime.stop())
            assertIs<PostgresState.Running>(runtime.start())
            val (restartedApplications, restartedDraws) = services()
            val restored = requireNotNull(restartedApplications.history.value.current)
            assertEquals(imported.record, restored)
            assertEquals(500, restored.applications.athleteCount)
            assertEquals(drawn.categories.map { it.shown }, restartedDraws.board().categories.map { it.shown })
        } finally {
            runtime.stop()
            root.toFile().deleteRecursively()
        }
    }
}
