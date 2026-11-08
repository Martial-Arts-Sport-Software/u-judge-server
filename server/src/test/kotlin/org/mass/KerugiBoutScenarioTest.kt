package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCommand
import org.mass.bout.BoutCorner
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStartSpec
import org.mass.bout.BoutStateAction
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutService
import org.mass.bout.RosterJudge
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.persistence.JdbcDomainEventStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I2a acceptance over the production module and the JDBC journal: three simulated judges and the operator judge one
 * Kerugi bout through the ADR-009 contract, including duplicates, out-of-order taps, a reconnect, a restart and rejections.
 */
class KerugiBoutScenarioTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private var clock = Instant.parse("2026-11-08T10:00:00Z")
    private val pairing = PairingRequests()
    private val credentials = (1..4).associate { number -> "android-$number" to approve("android-$number", "Судья$number") }
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:kerugi-bout-scenario-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }

    @Test
    fun `three judges and the operator judge a bout that survives duplicates, a reconnect and a restart`() {
        var sessionId: SessionId? = null
        lateinit var firstTap: JsonObject
        testApplication {
            val hub = start()
            val judges = (1..3).map { judge("android-$it").connect() }
            judges.forEach { assertNull(it.resync()["session"] as? JsonObject) }

            val started = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(id(), spec()))).sessionId
            sessionId = started
            judges.forEachIndexed { index, judge ->
                val snapshot = judge.push("session_snapshot").getValue("session").jsonObject
                assertEquals("prepared", snapshot.string("state"))
                assertEquals(7, snapshot.getValue("boutNumber").jsonPrimitive.int)
                assertEquals(index + 1, snapshot.getValue("seat").jsonObject.getValue("number").jsonPrimitive.int)
                assertEquals("Иванов И. И. (Москва)", snapshot.getValue("blue").jsonObject.string("label"))
            }
            val early = judges[0].tap("BLUE", "HEAD", clock)
            assertEquals("session_not_started", judges[0].command(early).string("code"))

            hub.operator("Оператор", BoutCommand.ChangeState(id(), started, BoutStateAction.START))
            judges.forEach { assertEquals("running", it.push("session_state_updated").string("state")) }

            // Out of order: the later tap arrives first; both are inside the 1000 ms window.
            val later = judges[1].tap("BLUE", "HEAD", clock.plusMillis(600))
            firstTap = judges[0].tap("BLUE", "HEAD", clock.plusMillis(100))
            assertEquals("command_ack", judges[1].command(later).string("type"))
            assertEquals("command_ack", judges[0].command(firstTap).string("type"))
            judges.forEach { assertEquals(2, it.push("kerugi_score_updated").score("blue")) }

            // A duplicate gets the original ACK and no push; the same ID with other content conflicts.
            assertEquals("command_ack", judges[0].command(firstTap).string("type"))
            val conflict = judges[0].command(JsonObject(firstTap + ("area" to JsonPrimitive("BODY"))))
            assertEquals("event_id_conflict", conflict.string("code"))
            assertEquals(firstTap.string("eventId"), conflict.string("eventId"))

            // A lone judge is audited, not scored.
            assertEquals("command_ack", judges[2].command(judges[2].tap("RED", "BODY", clock.plusMillis(3_000))).string("type"))

            // Judge 1 drops out; the operator scores 20 s into the round; judge 1 returns to the same seat.
            val seat = judges[0].session!!.getValue("seat")
            judges[0].close()
            clock = clock.plusSeconds(20)
            hub.operator("Оператор", BoutCommand.AwardAction(id(), started, KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 2))
            assertEquals(2, judges[1].push("kerugi_score_updated").score("red"))
            val returned = judge("android-1").connect()
            val resynced = returned.resync().getValue("session").jsonObject
            assertEquals(seat, resynced.getValue("seat"))
            assertEquals(2, resynced.getValue("score").jsonObject.getValue("red").jsonPrimitive.int)
            assertEquals("command_ack", returned.command(firstTap).string("type"))
            clock = clock.plusSeconds(30)
        }

        // Restart: a new process over the same journal gives the same bout, seats and score; the clock stands at the
        // latest saved event (ADR-010 §3), 20 s into round 1, and waits for the operator.
        testApplication {
            val hub = start()
            assertEquals(sessionId, hub.bouts.current()?.sessionId)
            val judge = judge("android-2").connect()
            val session = judge.resync().getValue("session").jsonObject
            assertEquals("paused", session.string("state"))
            assertEquals(timer("round", 1, remaining = 100_000, running = false), session.getValue("timer"))
            assertEquals(2, session.getValue("score").jsonObject.getValue("blue").jsonPrimitive.int)
            assertEquals(2, session.getValue("score").jsonObject.getValue("red").jsonPrimitive.int)
            assertEquals("command_ack", judge("android-1").connect().command(firstTap).string("type"))
            assertEquals(2, hub.bouts.current()!!.score().blue)

            assertEquals(BoutOutcome.Rejected("bout_not_finished"), hub.operator("Оператор", BoutCommand.CompleteBout(id(), sessionId!!)))
            hub.operator("Оператор", BoutCommand.ChangeState(id(), sessionId!!, BoutStateAction.RESUME))
            val resumed = judge.push("session_state_updated")
            assertEquals("running", resumed.string("state"))
            assertEquals(timer("round", 1, remaining = 100_000, running = true, since = clock), resumed.getValue("timer"))

            // The server timer ends round 1 at its deadline; the break runs from that instant.
            val deadline = clock.plusSeconds(100)
            clock = deadline.plusMillis(150)
            hub.tick()
            val breakStarted = judge.push("session_state_updated")
            assertEquals("paused", breakStarted.string("state"))
            assertEquals(timer("break", 2, remaining = 60_000, duration = 60_000, running = true, since = deadline), breakStarted.getValue("timer"))
            assertEquals("command_ack", judge.command(judge.tap("BLUE", "HEAD", clock)).string("type"))
            assertEquals(2, hub.bouts.current()!!.score().blue)

            // Round 2 after the break, a spin, the end of the round by the operator and the result by the final score.
            hub.operator("Оператор", BoutCommand.ChangeState(id(), sessionId!!, BoutStateAction.RESUME))
            assertEquals(timer("round", 2, remaining = 120_000, running = true, since = clock), judge.push("session_state_updated").getValue("timer"))
            hub.operator("Оператор", BoutCommand.AwardAction(id(), sessionId!!, KerugiOperatorActionType.SPIN_BONUS, KerugiCompetitor.BLUE, 1))
            assertEquals(3, judge.push("kerugi_score_updated").score("blue"))
            clock = clock.plusSeconds(10)
            hub.operator("Оператор", BoutCommand.EndPeriod(id(), sessionId!!))
            assertEquals(timer("round", 2, remaining = 0, running = false), judge.push("session_state_updated").getValue("timer"))
            hub.operator("Оператор", BoutCommand.CompleteBout(id(), sessionId!!))
            val completed = judge.push("session_state_updated")
            assertEquals("completed", completed.string("state"))
            assertEquals("BLUE", completed.getValue("result").jsonObject.string("winner"))
            assertEquals("final_score", completed.getValue("result").jsonObject.string("reason"))
            assertEquals("session_closed", judge.command(judge.tap("RED", "HEAD", clock)).string("code"))
        }
    }

    @Test
    fun `judges move to the next bout on the same socket and a stale tap is refused`() = testApplication {
        val hub = start()
        val judges = (1..3).map { judge("android-$it").connect().also { judge -> judge.resync() } }
        val first = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(id(), spec()))).sessionId
        judges.forEach { it.push("session_snapshot") }
        hub.operator("Оператор", BoutCommand.ChangeState(id(), first, BoutStateAction.START))
        judges.forEach { it.push("session_state_updated") }
        val stale = judges[0].tap("BLUE", "HEAD", clock)
        hub.operator("Оператор", BoutCommand.AwardAction(id(), first, KerugiOperatorActionType.THROW, KerugiCompetitor.BLUE, 1))
        hub.operator("Оператор", BoutCommand.EndPeriod(id(), first))
        hub.operator("Оператор", BoutCommand.ChangeState(id(), first, BoutStateAction.RESUME))
        hub.operator("Оператор", BoutCommand.EndPeriod(id(), first))
        hub.operator("Оператор", BoutCommand.CompleteBout(id(), first))
        judges.forEach { judge ->
            judge.push("kerugi_score_updated")
            repeat(3) { judge.push("session_state_updated") }
            assertEquals("completed", judge.push("session_state_updated").string("state"))
        }

        // «Начать» of the next bout: every judge gets the new session on the socket it already has (ADR-010 §7).
        val next = spec().copy(boutNumber = 8, index = 1, judges = listOf(3, 1).map { RosterJudge("android-$it", "Судья$it") })
        val second = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(id(), next))).sessionId
        val snapshots = judges.map { it.push("session_snapshot").getValue("session").jsonObject }
        snapshots.forEach { assertEquals(second.value, it.string("sessionId")) }
        assertEquals(8, snapshots[0].getValue("boutNumber").jsonPrimitive.int)
        assertEquals(2, snapshots[0].getValue("seat").jsonObject.getValue("number").jsonPrimitive.int)
        assertNull(snapshots[1]["seat"] as? JsonObject)
        assertEquals(1, snapshots[2].getValue("seat").jsonObject.getValue("number").jsonPrimitive.int)
        assertEquals(timer("round", 1, remaining = 120_000, running = false), snapshots[0].getValue("timer"))

        // A tap built from the old snapshot is refused; the unseated judge has no seat to tap from.
        assertEquals("session_closed", judges[0].command(stale).string("code"))
        hub.operator("Оператор", BoutCommand.ChangeState(id(), second, BoutStateAction.START))
        judges.forEach { assertEquals("running", it.push("session_state_updated").string("state")) }
        assertEquals("command_ack", judges[0].command(judges[0].tap("RED", "HEAD", clock)).string("type"))
        val foreign = JsonObject(scoreCommandFor(snapshots[0], id().value, clock) + ("deviceId" to JsonPrimitive("android-2")))
        assertEquals("judge_not_assigned", judges[1].command(foreign).string("code"))
        assertEquals(first, hub.bouts.bout(first)!!.sessionId)
        assertEquals("BLUE", hub.bouts.bout(first)!!.result!!.winner.name)
    }

    @Test
    fun `a device without a seat, an unsubscribed socket and malformed or forbidden messages change nothing`() = testApplication {
        val hub = start()
        val seated = judge("android-1").connect()
        val unseated = judge("android-4").connect()
        val silent = judge("android-2").connect()
        seated.resync()
        val sessionId = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(id(), spec()))).sessionId
        hub.operator("Оператор", BoutCommand.ChangeState(id(), sessionId, BoutStateAction.START))
        seated.push("session_snapshot")
        seated.push("session_state_updated")

        val watcher = unseated.resync().getValue("session").jsonObject
        assertNull(watcher["seat"] as? JsonObject)
        assertTrue(watcher.containsKey("seat"))
        val forged = JsonObject(seated.tap("BLUE", "HEAD", clock) + ("deviceId" to JsonPrimitive("android-4")))
        assertEquals("judge_not_assigned", unseated.command(forged).string("code"))

        val operatorCommand = """{"type":"session_lifecycle_command","eventId":"${id().value}"}"""
        assertEquals("command_not_permitted", seated.raw(operatorCommand).string("code"))
        val generic = """{"type":"command","eventId":"event-1","sequence":1,"clientTimestamp":"2026-11-08T10:00:00Z","sessionId":"s","payload":{"type":"kerugi_score"}}"""
        val unsupported = seated.raw(generic)
        assertEquals("unsupported_command", unsupported.string("code"))
        assertEquals("event-1", unsupported.string("eventId"))
        val malformed = JsonObject(seated.tap("BLUE", "HEAD", clock) + ("area" to JsonPrimitive("LEG")))
        val invalid = seated.command(malformed)
        assertEquals("invalid_command", invalid.string("code"))
        val extra = JsonObject(seated.tap("BLUE", "HEAD", clock) + ("points" to JsonPrimitive(2)))
        assertEquals("invalid_command", seated.command(extra).string("code"))
        val badResync = seated.raw("""{"type":"resync_request","requestId":"r-1"}""")
        assertEquals("resync_rejected", badResync.string("type"))
        assertEquals("invalid_resync_request", badResync.string("code"))
        assertEquals("r-1", badResync.string("requestId"))

        // The socket that never resynced gets no pushes: its next frame is the reply to its own request.
        assertEquals("heartbeat_ack", silent.raw("""{"type":"heartbeat"}""").string("type"))
        assertEquals(0, hub.bouts.current()!!.score().blue)
        assertEquals(2, hub.bouts.current()!!.events.size)
    }

    private fun ApplicationTestBuilder.start(): RealtimeHub {
        val hub = RealtimeHub(KerugiBoutService(JdbcBoutEventJournal(JdbcDomainEventStore(dataSource)), peerId, now = { clock }))
        application { module(pairingRequests = pairing, realtime = hub) }
        return hub
    }

    private fun ApplicationTestBuilder.judge(deviceId: String) =
        JudgeSimulator(createClient { install(WebSockets) }, credentials.getValue(deviceId), deviceId)

    private fun approve(deviceId: String, surname: String): String {
        val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(deviceId, surname, "android")))
        return assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential
    }

    private fun spec() = BoutStartSpec(
        competitionId = CompetitionId("3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60"),
        bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40"),
        categoryId = "category",
        categoryTitle = "Весовая категория · Юноши · до 45 кг",
        boutNumber = 7,
        round = 1,
        index = 0,
        blue = BoutCorner("entry-1", "Иванов И. И. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
        red = BoutCorner("entry-2", "Петров П. П. (Казань)", listOf(BoutAthlete("Петров Пётр Петрович", "Казань"))),
        judges = (1..3).map { RosterJudge("android-$it", "Судья$it") },
    )

    private fun id() = EventId(UUID.randomUUID().toString())

    private fun timer(period: String, round: Int, remaining: Long, running: Boolean, duration: Long = 120_000, since: Instant? = null) =
        realtimeJson.parseToJsonElement(
            realtimeJson.encodeToString(
                TimerView(period, round, round == 3, duration, remaining, running, since?.toString()),
            ),
        )

    private fun JsonObject.score(side: String) = getValue("score").jsonObject.getValue(side).jsonPrimitive.int
}
