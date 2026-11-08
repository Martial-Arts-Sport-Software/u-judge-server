package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCorner
import org.mass.bout.BoutOutcome
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutService
import org.mass.bout.RosterJudge
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.TechnicalDiscipline
import org.mass.performance.PerformanceCommand
import org.mass.performance.PerformanceStartSpec
import org.mass.performance.TechnicalPerformanceService
import org.mass.persistence.JdbcDomainEventStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * I5 acceptance over the production module and the JDBC journal (ADR-011): three simulated judges send their final
 * Hosinsool vectors through `technical_score_send_command`; duplicates, a lost ACK, a Send made while disconnected, a
 * restart and every rejection keep exactly one final score per seat, and the server total follows the normative formula.
 */
class TechnicalPerformanceScenarioTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private var clock = Instant.parse("2026-11-08T12:00:00Z")
    private val pairing = PairingRequests()
    private val credentials = (1..3).associate { number -> "android-$number" to approve("android-$number", "Судья$number") }
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:technical-scenario-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }

    private val vectors = listOf(
        Triple(listOf(8, 7, 9, 6), listOf(7, 8, 8, 9), 3),
        Triple(listOf(8, 8, 8, 8), listOf(8, 8, 8, 8), 0),
        Triple(listOf(9, 9, 8, 9), listOf(9, 8, 9, 9), 0),
    )

    @Test
    fun `three judges send final vectors that survive duplicates, a lost ACK, a disconnect and a restart`() {
        var sessionId: SessionId? = null
        lateinit var firstSend: JsonObject
        testApplication {
            val hub = start()
            val judges = (1..3).map { judge("android-$it").connect() }
            judges.forEach { assertNull(it.resync()["session"] as? JsonObject) }

            val started = assertIs<BoutOutcome.Applied>(hub.performanceOperator("Оператор", PerformanceCommand.Start(id(), spec()))).sessionId
            sessionId = started
            judges.forEachIndexed { index, judge ->
                val snapshot = judge.push("session_snapshot").getValue("session").jsonObject
                assertEquals("hosinsool", snapshot.string("discipline"))
                assertEquals("prepared", snapshot.string("state"))
                assertEquals(12, snapshot.getValue("performanceNumber").jsonPrimitive.int)
                assertEquals(4, snapshot.getValue("criteria").jsonObject.getValue("technique").jsonPrimitive.int)
                assertEquals("trimmed_mean", snapshot.getValue("settings").jsonObject.string("formula"))
                assertEquals(index + 1, snapshot.getValue("seat").jsonObject.getValue("number").jsonPrimitive.int)
                assertEquals(JsonNull, snapshot.getValue("sent"))
                assertEquals(JsonNull, snapshot.getValue("result"))
            }
            val early = judges[0].send(0)
            assertEquals("session_not_started", judges[0].command(early).string("code"))

            hub.performanceOperator("Оператор", PerformanceCommand.Begin(id(), started))
            judges.forEach {
                val state = it.push("session_state_updated")
                assertEquals("running", state.string("state"))
                assertEquals(JsonNull, state.getValue("result"))
            }

            firstSend = judges[0].send(0)
            assertEquals("command_ack", judges[0].command(firstSend).string("type"))
            judges.forEach { assertEquals(listOf(1), it.push("technical_score_updated").submitted()) }

            // Send is final: the identical retry is acknowledged again, anything else is refused (`TEC-008`).
            assertEquals("command_ack", judges[0].command(firstSend).string("type"))
            assertEquals("event_id_conflict", judges[0].command(JsonObject(firstSend + ("deductionTenths" to JsonPrimitive(0)) +
                ("totalTenths" to JsonPrimitive(62)))).string("code"))
            assertEquals("score_already_sent", judges[0].command(judges[0].send(1)).string("code"))

            // Rejections change nothing (`TEC-002`, `TEC-007`, `TAN-005`).
            assertEquals("total_mismatch", judges[1].command(judges[1].send(1, total = 63)).string("code"))
            val invalid = judges[1].technical(listOf(0, 8, 8, 8), listOf(8, 8, 8, 8), 0, clock)
            assertEquals("invalid_score_vector", judges[1].command(invalid).string("code"))
            assertEquals("invalid_score_vector", judges[1].command(judges[1].technical(List(6) { 8 }, List(4) { 8 }, 0, clock)).string("code"))
            val otherDiscipline = JsonObject(judges[1].send(1) + ("discipline" to JsonPrimitive("pair_freestyle")))
            assertEquals("discipline_mismatch", judges[1].command(otherDiscipline).string("code"))
            assertEquals("discipline_mismatch", judges[1].command(judges[1].tap("BLUE", "HEAD", clock)).string("code"))
            assertEquals("discipline_mismatch", judges[1].command(judges[1].tanbon(null, "CROSS", clock)).string("code"))
            val stringly = JsonObject(judges[1].send(1) + ("techniqueTenths" to JsonPrimitive("8,8,8,8")))
            assertEquals("invalid_command", judges[1].command(stringly).string("code"))
            val extra = JsonObject(judges[1].send(1) + ("comment" to JsonPrimitive("ok")))
            assertEquals("invalid_command", judges[1].command(extra).string("code"))

            // Judge 2 scores while disconnected: the outbox keeps the Send and replays the same event after reconnect.
            val offline = judges[1].send(1)
            judges[1].close()
            val returned = judge("android-2").connect()
            assertEquals(JsonNull, returned.resync().getValue("session").jsonObject.getValue("sent"))
            assertEquals("command_ack", returned.command(JsonObject(offline + ("judgeId" to returned.session!!.getValue("seat").jsonObject.getValue("judgeId")))).string("type"))

            // Judge 3 loses the ACK: the replay of the same event is acknowledged and stays one Send.
            val lost = judges[2].send(2)
            judges[2].fire(lost)
            judges[2].close()
            val third = judge("android-3").connect()
            third.resync()
            assertEquals("command_ack", third.command(lost).string("type"))
            val resynced = third.resync().getValue("session").jsonObject
            assertEquals(lost.string("eventId"), resynced.getValue("sent").jsonObject.string("eventId"))
            assertEquals(70, resynced.getValue("sent").jsonObject.getValue("totalTenths").jsonPrimitive.int)
            assertEquals(listOf(1, 2, 3), resynced.submitted())
            assertEquals(3, hub.performances.current()!!.sends.size)
        }

        // Restart: a new process over the same journal gives the same Sends; the original retry is still acknowledged.
        testApplication {
            val hub = start()
            assertEquals(sessionId, hub.performances.current()?.sessionId)
            val judge = judge("android-1").connect()
            val session = judge.resync().getValue("session").jsonObject
            assertEquals("running", session.string("state"))
            assertEquals(firstSend.string("eventId"), session.getValue("sent").jsonObject.string("eventId"))
            assertEquals(59, session.getValue("sent").jsonObject.getValue("totalTenths").jsonPrimitive.int)
            assertEquals("command_ack", judge.command(firstSend).string("type"))

            hub.performanceOperator("Оператор", PerformanceCommand.Complete(id(), sessionId!!))
            assertEquals("completed", judge.push("session_state_updated").string("state"))
            val result = judge.push("technical_score_updated").getValue("score").jsonObject.getValue("result").jsonObject
            // Totals 5.9, 6.4 and 7.0: the trimmed mean keeps 6.4 with divisor 1.
            assertEquals(64, result.getValue("totalTenths").jsonPrimitive.int)
            assertEquals(1, result.getValue("divisor").jsonPrimitive.int)
            assertEquals("trimmed_mean", result.string("formula"))
            assertEquals(64, hub.performances.current()!!.result()!!.totalTenths)
            assertEquals("session_closed", judge.command(judge.send(1)).string("code"))
        }
    }

    private fun JudgeSimulator.send(vector: Int, total: Int? = null, at: Instant = clock): JsonObject {
        val (technique, presentation, deduction) = vectors[vector]
        return if (total == null) technical(technique, presentation, deduction, at) else technical(technique, presentation, deduction, at, total = total)
    }

    private fun JsonObject.submitted() = getValue("score").jsonObject.getValue("submittedSeats").jsonArray.map { it.jsonPrimitive.int }

    private fun ApplicationTestBuilder.start(): RealtimeHub {
        val journal = JdbcBoutEventJournal(JdbcDomainEventStore(dataSource))
        val hub = RealtimeHub(KerugiBoutService(journal, peerId, now = { clock }), TechnicalPerformanceService(journal, peerId, now = { clock }))
        application { module(pairingRequests = pairing, realtime = hub) }
        return hub
    }

    private fun ApplicationTestBuilder.judge(deviceId: String) =
        JudgeSimulator(createClient { install(WebSockets) }, credentials.getValue(deviceId), deviceId)

    private fun approve(deviceId: String, surname: String): String {
        val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(deviceId, surname, "android")))
        return assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential
    }

    private fun spec() = PerformanceStartSpec(
        competitionId = CompetitionId("3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60"),
        bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c41"),
        categoryId = "self-defense",
        categoryTitle = "Приёмы самообороны · 2013-2011 г.р. Юноши",
        performanceNumber = 12,
        round = 1,
        index = 0,
        entry = BoutCorner("entry-5", "Иванов И. И. (Москва) / Петров П. П. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
        discipline = TechnicalDiscipline.HOSINSOOL,
        techniqueCriteria = 4,
        judges = (1..3).map { RosterJudge("android-$it", "Судья$it") },
    )

    private fun id() = EventId(UUID.randomUUID().toString())
}
