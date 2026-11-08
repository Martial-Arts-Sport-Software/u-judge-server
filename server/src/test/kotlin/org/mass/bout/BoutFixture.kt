package org.mass.bout

import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiScoringArea
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertIs

/** A court with a fixed clock and a started Kerugi bout for bout tests. */
class BoutFixture(
    val journal: BoutEventJournal = BoutEventJournal.InMemory(),
    var clock: Instant = Instant.parse("2026-11-08T10:00:00Z"),
) {
    val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    val competitionId = CompetitionId("3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60")
    val bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40")
    val operator = BoutOrigin.Operator("Оператор")
    var service = newService()

    fun newService() = KerugiBoutService(journal, peerId, now = { clock })

    fun restart() {
        service = newService()
    }

    fun spec(
        judges: List<String> = listOf("android-1", "android-2", "android-3"),
        quorum: Int = 2,
        windowMillis: Long = 1_000,
        index: Int = 0,
        timing: BoutTiming = BoutTiming.of(org.mass.applications.AgeClass.YOUTH),
    ) = BoutStartSpec(
        competitionId = competitionId,
        bracketId = bracketId,
        categoryId = "category",
        categoryTitle = "Весовая категория · Юноши · до 45 кг",
        boutNumber = 7 + index,
        round = 1,
        index = index,
        blue = BoutCorner("entry-1", "Иванов И. И. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
        red = BoutCorner("entry-2", "Петров П. П. (Казань)", listOf(BoutAthlete("Петров Пётр Петрович", "Казань"))),
        judges = judges.mapIndexed { number, device -> RosterJudge(device, "Судья${number + 1}") },
        quorum = quorum,
        coincidenceWindowMillis = windowMillis,
        timing = timing,
    )

    /** Starts the bout and, unless [running] is false, the session. */
    fun start(spec: BoutStartSpec = spec(), running: Boolean = true): SessionId {
        val applied = assertIs<BoutOutcome.Applied>(operator(BoutCommand.StartBout(id(), spec)))
        if (running) assertIs<BoutOutcome.Applied>(state(applied.sessionId, BoutStateAction.START))
        return applied.sessionId
    }

    fun operator(command: BoutCommand) = service.dispatch(operator, command)

    fun state(sessionId: SessionId, action: BoutStateAction) = operator(BoutCommand.ChangeState(id(), sessionId, action))

    fun tick() = service.tick()

    fun endPeriod(sessionId: SessionId) = operator(BoutCommand.EndPeriod(id(), sessionId))

    /** «Стоп периода» on round 1, «Продолжить» from the break and «Стоп периода» on round 2 (ADR-010 §2). */
    fun finishRegularTime(sessionId: SessionId) {
        assertIs<BoutOutcome.Applied>(endPeriod(sessionId))
        assertIs<BoutOutcome.Applied>(state(sessionId, BoutStateAction.RESUME))
        assertIs<BoutOutcome.Applied>(endPeriod(sessionId))
    }

    fun context(device: String, bout: KerugiBout = requireNotNull(service.current())) = JudgeContext(
        competitionId = bout.competitionId,
        peerId = bout.peerId,
        courtId = bout.courtId,
        bracketId = bout.bracketId,
        sessionId = bout.sessionId,
        judgeId = org.mass.domain.JudgeId(requireNotNull(bout.seatOf(device)).judgeId),
        deviceId = device,
    )

    fun score(
        device: String,
        competitor: KerugiCompetitor = KerugiCompetitor.BLUE,
        area: KerugiScoringArea = KerugiScoringArea.HEAD,
        afterMillis: Long = 0,
        eventId: EventId = id(),
        context: JudgeContext = context(device),
    ) = BoutCommand.SubmitScore(eventId, 0, clock.plusMillis(afterMillis), context, competitor, area)

    fun judge(device: String, command: BoutCommand) = service.dispatch(BoutOrigin.Judge(device), command)

    fun advance(millis: Long) {
        clock = clock.plus(Duration.ofMillis(millis))
    }

    fun id() = EventId(UUID.randomUUID().toString())
}
