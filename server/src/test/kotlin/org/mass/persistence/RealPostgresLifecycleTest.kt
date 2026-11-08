package org.mass.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mass.RealtimeHub
import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCorner
import org.mass.bout.BoutDiscipline
import org.mass.bout.RosterJudge
import org.mass.bout.TanbonArea
import org.mass.domain.BracketId
import org.mass.domain.JudgeVector
import org.mass.domain.TechnicalDiscipline
import org.mass.performance.PerformanceCommand
import org.mass.performance.PerformanceRejection
import org.mass.performance.PerformanceStartSpec
import org.mass.performance.TechnicalPerformanceService
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
import org.mass.PairingRequests
import org.mass.bout.CourtBouts
import org.mass.report.CompetitionReportTest
import org.mass.report.CompetitionReports
import org.mass.report.CompetitionScenario
import org.mass.report.CsvReport
import org.mass.report.XlsxReport
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.assertFailsWith
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
    @Test
    fun `recovers a Tanbon bout and a technical performance in the middle with the same Sends after a PostgreSQL restart`() {
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
        var clock = Instant.parse("2026-11-08T12:00:00Z")
        fun hub(): RealtimeHub {
            val dataSource = requireNotNull(runtime.dataSource)
            val journal = JdbcBoutEventJournal(JdbcDomainEventStore(dataSource))
            val peerId = LocalPeerIdentity.loadOrCreate(dataSource)
            return RealtimeHub(KerugiBoutService(journal, peerId, now = { clock }), TechnicalPerformanceService(journal, peerId, now = { clock }))
        }
        val operator = "Оператор"
        val judges = listOf("android-1", "android-2", "android-3")

        try {
            assertIs<PostgresState.Running>(runtime.start())
            val first = hub()
            // A Tanbon bout with an audit-only CROSS is decided before the performance starts on the same court.
            val tanbon = assertIs<BoutOutcome.Applied>(
                first.operator(operator, BoutCommand.StartBout(EventId.new(), testBoutSpec(judges.take(2), quorum = 1).copy(discipline = BoutDiscipline.TANBON))),
            ).sessionId
            first.operator(operator, BoutCommand.ChangeState(EventId.new(), tanbon, BoutStateAction.START))
            val bout = requireNotNull(first.bouts.current())
            fun tanbonTap(competitor: KerugiCompetitor?, area: TanbonArea, millis: Long) = BoutCommand.SubmitTanbonScore(
                EventId.new(), 0, clock.plusMillis(millis),
                JudgeContext(bout.competitionId, bout.peerId, bout.courtId, bout.bracketId, bout.sessionId,
                    JudgeId(requireNotNull(bout.seatOf("android-1")).judgeId), "android-1"),
                competitor, area,
            )
            assertIs<BoutOutcome.Applied>(first.bouts.dispatch(BoutOrigin.Judge("android-1"), tanbonTap(KerugiCompetitor.RED, TanbonArea.HEAD, 100)))
            assertIs<BoutOutcome.Applied>(first.bouts.dispatch(BoutOrigin.Judge("android-1"), tanbonTap(null, TanbonArea.CROSS, 200)))
            clock = clock.plusSeconds(5)
            // «Стоп периода» on round 1, «Продолжить» from the break, «Стоп периода» on round 2 (ADR-010 §2).
            assertIs<BoutOutcome.Applied>(first.operator(operator, BoutCommand.EndPeriod(EventId.new(), tanbon)))
            assertIs<BoutOutcome.Applied>(first.operator(operator, BoutCommand.ChangeState(EventId.new(), tanbon, BoutStateAction.RESUME)))
            assertIs<BoutOutcome.Applied>(first.operator(operator, BoutCommand.EndPeriod(EventId.new(), tanbon)))
            assertIs<BoutOutcome.Applied>(first.operator(operator, BoutCommand.CompleteBout(EventId.new(), tanbon)))

            val spec = PerformanceStartSpec(
                competitionId = bout.competitionId,
                bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c41"),
                categoryId = "self-defense",
                categoryTitle = "Приёмы самообороны · Юниоры",
                performanceNumber = 12,
                round = 1,
                index = 0,
                entry = BoutCorner("entry-5", "Иванов И. И. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
                discipline = TechnicalDiscipline.HOSINSOOL,
                techniqueCriteria = 6,
                judges = judges.mapIndexed { index, device -> RosterJudge(device, "Судья${index + 1}") },
            )
            val performanceId = assertIs<BoutOutcome.Applied>(first.performanceOperator(operator, PerformanceCommand.Start(EventId.new(), spec))).sessionId
            first.performanceOperator(operator, PerformanceCommand.Begin(EventId.new(), performanceId))
            val performance = requireNotNull(first.performances.current())
            fun send(device: String, criterion: Int) = JudgeVector(List(6) { criterion }, List(4) { criterion }, 0).let { vector ->
                PerformanceCommand.SendScore(
                    EventId.new(), 0, clock,
                    JudgeContext(performance.competitionId, performance.peerId, performance.courtId, performance.bracketId,
                        performance.sessionId, JudgeId(requireNotNull(performance.seatOf(device)).judgeId), device),
                    "hosinsool", vector, vector.totalTenths,
                )
            }
            val firstSend = send("android-1", 7)
            assertIs<BoutOutcome.Applied>(first.performances.dispatch(BoutOrigin.Judge("android-1"), firstSend))
            assertIs<BoutOutcome.Applied>(first.performances.dispatch(BoutOrigin.Judge("android-2"), send("android-2", 8)))

            assertEquals(PostgresState.Stopped, runtime.stop())
            assertIs<PostgresState.Running>(runtime.start())
            val restarted = hub()

            val decided = requireNotNull(restarted.bouts.bout(tanbon))
            assertEquals(BoutDiscipline.TANBON, decided.discipline)
            assertEquals(2, decided.score().red)
            assertEquals(KerugiCompetitor.RED, decided.result?.winner)
            val current = requireNotNull(restarted.performances.current())
            assertEquals(performanceId, current.sessionId)
            assertEquals(SessionState.RUNNING, current.state)
            assertEquals(listOf(1, 2), current.submittedSeats())
            assertEquals(false, assertIs<BoutOutcome.Applied>(restarted.performances.dispatch(BoutOrigin.Judge("android-1"), firstSend)).isNew)
            assertEquals(
                BoutOutcome.Rejected(PerformanceRejection.SCORE_ALREADY_SENT),
                restarted.performances.dispatch(BoutOrigin.Judge("android-1"), send("android-1", 9)),
            )
            assertIs<BoutOutcome.Applied>(restarted.performances.dispatch(BoutOrigin.Judge("android-3"), send("android-3", 9)))
            assertIs<BoutOutcome.Applied>(restarted.performanceOperator(operator, PerformanceCommand.Complete(EventId.new(), performanceId)))
            // Totals 7.0, 8.0 and 9.0: the trimmed mean keeps 8.0.
            assertEquals(80, requireNotNull(restarted.performances.current()?.result()).totalTenths)
            assertEquals(maxOf(restarted.bouts.cursor, restarted.performances.cursor), restarted.cursor)
        } finally {
            runtime.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restores a judged competition from its backup into a new database of a clean cluster with identical exports`() {
        val installationDirectory = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(
            !installationDirectory.isNullOrBlank(),
            "Set -DuJudge.postgres.installationDirectory to a PostgreSQL bundle root to run this acceptance test",
        )
        val root = createTempDirectory()
        fun cluster(name: String) = ManagedPostgresRuntime(
            PostgresRuntimeConfiguration(
                installationDirectory = Path.of(installationDirectory),
                applicationDataDirectory = root.resolve(name),
                port = PostgresCommand.withAvailableLoopbackPort(listOf("postgres")).port,
                platform = PostgresPlatform.current(),
            ),
        )
        val generatedAt = Instant.parse("2026-11-08T16:00:00Z")
        val zone = ZoneId.of("Europe/Moscow")
        /** The services of one desktop over [dataSource], as `ServerRuntime` composes them. */
        fun reports(dataSource: DataSource, judge: Boolean = false): CompetitionReports {
            val peerId = LocalPeerIdentity.loadOrCreate(dataSource)
            val store = JdbcDomainEventStore(dataSource)
            val pairing = PairingRequests()
            val applications = CompetitionApplicationsService(JdbcCompetitionApplicationsJournal(store, peerId), backup = null)
            val draws = CompetitionDrawService(JdbcCompetitionDrawJournal(store, peerId), applications.history, peerId, seeds = { 11L })
            val journal = JdbcBoutEventJournal(store)
            val hub = RealtimeHub(KerugiBoutService(journal, peerId), TechnicalPerformanceService(journal, peerId))
            val courts = CompetitionCourtsService(draws, peerId) { CourtBouts.progress(hub) }
            if (judge) {
                CompetitionScenario(applications, draws, CourtBouts(hub, draws, courts, pairing), pairing).apply {
                    importAndDraw()
                    connectJudges()
                    judgeEverything()
                }
            }
            return CompetitionReports(applications, courts, hub) { zone }
        }
        val source = cluster("source")
        val clean = cluster("clean")
        try {
            assertIs<PostgresState.Running>(source.start())
            val sourceData = requireNotNull(source.dataSource)
            val before = requireNotNull(reports(sourceData, judge = true).report(generatedAt))
            val backup = JournalBackups(root.resolve("backups"), sourceData).create(JournalBackups.MANUAL)
            val file = assertIs<BackupInspection.Valid>(JournalBackup.inspect(backup)).backup
            // A restore never writes into a journal that has rows (`AUD-001`).
            assertEquals(JournalRestore.Failed(JournalBackup.TARGET_NOT_EMPTY), JournalBackup.restore(file, sourceData, Instant.now()))
            assertEquals(PostgresState.Stopped, source.stop())

            assertIs<PostgresState.Running>(clean.start())
            clean.createDatabase("u_judge_r1")
            val target = clean.dataSource("u_judge_r1")
            val restored = assertIs<JournalRestore.Restored>(JournalBackup.restore(file, target, Instant.now()))
            assertEquals(file.header.sha256, restored.sha256)
            val after = requireNotNull(reports(target).report(generatedAt))
            assertEquals(before, after)
            assertEquals(String(CsvReport.render(before)), String(CsvReport.render(after)))
            assertEquals(
                CompetitionReportTest.cells(XlsxReport.render(before)),
                CompetitionReportTest.cells(XlsxReport.render(after)),
            )

            // The restored journal is durable: after a PostgreSQL restart the projections are the same.
            assertEquals(PostgresState.Stopped, clean.stop())
            assertIs<PostgresState.Running>(clean.start())
            assertEquals(before, reports(clean.dataSource("u_judge_r1")).report(generatedAt))
            // A failed restore target is dropped; the active database never is.
            clean.createDatabase("u_judge_r2")
            clean.dropDatabase("u_judge_r2")
            assertFailsWith<IllegalArgumentException> { clean.dropDatabase(PostgresRuntimeConfiguration.DEFAULT_DATABASE) }
        } finally {
            source.stop()
            clean.stop()
            root.toFile().deleteRecursively()
        }
    }
}
