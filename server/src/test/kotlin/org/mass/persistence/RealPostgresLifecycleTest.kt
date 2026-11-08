package org.mass.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mass.bout.BoutCommand
import org.mass.bout.BoutOrigin
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStateAction
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.JudgeContext
import org.mass.bout.KerugiBoutEvents
import org.mass.bout.KerugiBoutService
import org.mass.domain.EventId
import org.mass.domain.JudgeId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoringArea
import org.mass.domain.SessionState
import org.mass.testBoutSpec
import org.mass.applications.CompetitionApplicationsService
import org.mass.applications.ImportOutcome
import org.mass.applications.ImportPreparation
import org.mass.applications.JdbcCompetitionApplicationsJournal
import org.mass.applications.SyntheticApplications
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionDrawService
import org.mass.draw.CompetitionOfficials
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
    fun `imports and draws 500 participants atomically and restores them and the courts after a PostgreSQL restart`() {
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
        fun peerId() = LocalPeerIdentity.loadOrCreate(requireNotNull(runtime.dataSource))

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
            // Three courts with the last category first on court 3, judged here; officials; another next bout.
            val courts = CompetitionCourtsService(draws, peerId())
            val last = drawn.categories.last().category.id
            assertEquals(DrawOutcome.Done, courts.setCourtCount(3))
            assertEquals(DrawOutcome.Done, courts.placeCategory(last, court = 3, position = 0))
            assertEquals(DrawOutcome.Done, courts.selectLocalCourt(1))
            assertEquals(DrawOutcome.Done, courts.setOfficials(CompetitionOfficials("Новосибирск", "Смирнов А. А.", "Волкова Е. В.")))
            val picked = courts.courtPanel().queue.last().ref
            assertEquals(DrawOutcome.Done, courts.selectNextBout(picked))
            val arranged = courts.board()

            assertEquals(PostgresState.Stopped, runtime.stop())
            assertIs<PostgresState.Running>(runtime.start())
            val (restartedApplications, restartedDraws) = services()
            val restored = requireNotNull(restartedApplications.history.value.current)
            assertEquals(imported.record, restored)
            assertEquals(500, restored.applications.athleteCount)
            assertEquals(drawn.categories.map { it.shown }, restartedDraws.board().categories.map { it.shown })
            val restartedCourts = CompetitionCourtsService(restartedDraws, peerId())
            val reopened = restartedCourts.board()
            assertEquals(arranged.plan, reopened.plan)
            assertEquals(arranged.localCourt, reopened.localCourt)
            assertEquals(arranged.officials, reopened.officials)
            assertEquals(arranged.numbers, reopened.numbers)
            assertEquals(picked, restartedCourts.courtPanel().next?.ref)
        } finally {
            runtime.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recovers a Kerugi bout in the middle with the same score, seats and idempotent retry after a PostgreSQL restart`() {
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
        var clock = Instant.parse("2026-11-08T10:00:00Z")
        fun bouts(): KerugiBoutService {
            val dataSource = requireNotNull(runtime.dataSource)
            return KerugiBoutService(JdbcBoutEventJournal(JdbcDomainEventStore(dataSource)), LocalPeerIdentity.loadOrCreate(dataSource), now = { clock })
        }

        try {
            assertIs<PostgresState.Running>(runtime.start())
            val first = bouts()
            val operator = BoutOrigin.Operator("Оператор")
            val started = assertIs<BoutOutcome.Applied>(first.dispatch(operator, BoutCommand.StartBout(EventId.new(), testBoutSpec(listOf("android-1", "android-2")))))
            first.dispatch(operator, BoutCommand.ChangeState(EventId.new(), started.sessionId, BoutStateAction.START))
            val bout = requireNotNull(first.current())
            fun tap(device: String, millis: Long) = BoutCommand.SubmitScore(
                EventId.new(), 0, clock.plusMillis(millis),
                JudgeContext(bout.competitionId, bout.peerId, bout.courtId, bout.bracketId, bout.sessionId,
                    JudgeId(requireNotNull(bout.seatOf(device)).judgeId), device),
                KerugiCompetitor.BLUE, KerugiScoringArea.HEAD,
            )
            val firstTap = tap("android-1", 100)
            assertIs<BoutOutcome.Applied>(first.dispatch(BoutOrigin.Judge("android-1"), firstTap))
            assertIs<BoutOutcome.Applied>(first.dispatch(BoutOrigin.Judge("android-2"), tap("android-2", 400)))
            clock = clock.plusSeconds(12)
            first.dispatch(operator, BoutCommand.AwardAction(EventId.new(), started.sessionId, KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.BLUE, 1))
            // The timer saves the running clock 5 s after the latest event (ADR-010 §3).
            clock = clock.plusSeconds(6)
            assertEquals(KerugiBoutEvents.CLOCK_SAVED, first.tick()?.let { saved -> first.current()!!.events.last { it.sequence == saved.sequence }.event.type })
            val before = requireNotNull(first.current())

            clock = clock.plusSeconds(40)
            assertEquals(PostgresState.Stopped, runtime.stop())
            assertIs<PostgresState.Running>(runtime.start())
            val restarted = bouts()

            val after = requireNotNull(restarted.current())
            assertEquals(started.sessionId, after.sessionId)
            assertEquals(SessionState.PAUSED, after.state)
            assertEquals(before.score(), after.score())
            assertEquals(2, after.score().blue)
            assertEquals(1, after.score().red)
            assertEquals(before.started.seats, after.started.seats)
            // The clock stands at the saved checkpoint, 18 s into round 1, not at the wall time of the restart.
            assertEquals(120_000L - 18_000, after.clock.remainingMillis)
            assertEquals(false, after.clock.running)
            assertEquals(KerugiBoutEvents.CLOCK_STOPPED, after.events.last().event.type)
            assertEquals(after.lastSequence, restarted.cursor)
            assertEquals(false, assertIs<BoutOutcome.Applied>(restarted.dispatch(BoutOrigin.Judge("android-1"), firstTap)).isNew)
            assertEquals(before.events.size + 1, requireNotNull(restarted.current()).events.size)

            // A second restart of a stopped clock appends nothing; the operator resumes from the saved time.
            assertEquals(PostgresState.Stopped, runtime.stop())
            assertIs<PostgresState.Running>(runtime.start())
            val again = bouts()
            assertEquals(after.events.size, again.current()!!.events.size)
            assertIs<BoutOutcome.Applied>(again.dispatch(operator, BoutCommand.ChangeState(EventId.new(), started.sessionId, BoutStateAction.RESUME)))
            assertEquals(Instant.parse("2026-11-08T10:00:58Z"), again.current()!!.clock.runningSince)
            assertEquals(102_000L, again.current()!!.clock.remainingMillis)
        } finally {
            runtime.stop()
            root.toFile().deleteRecursively()
        }
    }
}
