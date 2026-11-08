package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.mass.bout.BoutCommand
import org.mass.bout.BoutDiscipline
import org.mass.bout.BoutEntryStatus
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStateAction
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutService
import org.mass.domain.EventId
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * I5 Tanbon acceptance over the production module and the JDBC journal (ADR-011 §3): judges score `HEAD` 2 and `BODY` 1
 * under the Kerugi quorum and window, `CROSS` is acknowledged and audited without changing the score, the other
 * discipline's commands are refused and a restart restores the bout.
 */
class TanbonBoutScenarioTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val clock = Instant.parse("2026-11-08T11:00:00Z")
    private val pairing = PairingRequests()
    private val credentials = (1..3).associate { number -> "android-$number" to approve("android-$number", "Судья$number") }
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:tanbon-scenario-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }

    @Test
    fun `three judges score a Tanbon bout with an audit-only CROSS that survives a restart`() {
        lateinit var cross: JsonObject
        testApplication {
            val hub = start()
            val judges = (1..3).map { judge("android-$it").connect().also { judge -> judge.resync() } }
            val spec = testBoutSpec(judges.map(JudgeSimulator::deviceId)).copy(discipline = BoutDiscipline.TANBON)
            val sessionId = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(id(), spec))).sessionId
            judges.forEach { assertEquals("tanbon", it.push("session_snapshot").getValue("session").jsonObject.string("discipline")) }
            hub.operator("Оператор", BoutCommand.ChangeState(id(), sessionId, BoutStateAction.START))
            judges.forEach { it.push("session_state_updated") }

            assertEquals("command_ack", judges[0].command(judges[0].tanbon("BLUE", "HEAD", clock.plusMillis(100))).string("type"))
            assertEquals("command_ack", judges[1].command(judges[1].tanbon("BLUE", "HEAD", clock.plusMillis(300))).string("type"))
            judges.forEach { assertEquals(2, it.push("tanbon_score_updated").score("blue")) }
            assertEquals("command_ack", judges[1].command(judges[1].tanbon("RED", "BODY", clock.plusMillis(2_000))).string("type"))
            assertEquals("command_ack", judges[2].command(judges[2].tanbon("RED", "BODY", clock.plusMillis(2_500))).string("type"))
            judges.forEach { assertEquals(1, it.push("tanbon_score_updated").score("red")) }

            cross = judges[0].tanbon(null, "CROSS", clock.plusMillis(4_000))
            assertEquals("command_ack", judges[0].command(cross).string("type"))
            assertEquals("command_ack", judges[1].command(judges[1].tanbon(null, "CROSS", clock.plusMillis(4_100))).string("type"))
            assertTrue(judges.none { judge -> judge.pending().any { it.string("type") == "tanbon_score_updated" } }, "CROSS pushes no score")

            val crossWithSide = JsonObject(judges[0].tanbon(null, "CROSS", clock) + ("competitor" to JsonPrimitive("BLUE")))
            assertEquals("invalid_command", judges[0].command(crossWithSide).string("code"))
            val headWithoutSide = judges[0].tanbon(null, "HEAD", clock)
            assertEquals("invalid_command", judges[0].command(headWithoutSide).string("code"))
            assertEquals("discipline_mismatch", judges[0].command(judges[0].tap("BLUE", "HEAD", clock)).string("code"))
            assertEquals(
                "discipline_mismatch",
                judges[0].command(judges[0].technical(List(4) { 8 }, List(4) { 8 }, 0, clock)).string("code"),
            )
        }

        testApplication {
            val hub = start()
            val judge = judge("android-1").connect()
            val session = judge.resync().getValue("session").jsonObject
            assertEquals("tanbon", session.string("discipline"))
            assertEquals(2, session.getValue("score").jsonObject.getValue("blue").jsonPrimitive.int)
            assertEquals(1, session.getValue("score").jsonObject.getValue("red").jsonPrimitive.int)
            assertEquals("command_ack", judge.command(cross).string("type"))
            val audit = hub.bouts.current()!!.history().single { it.eventId.value == cross.string("eventId") }
            assertEquals(BoutEntryStatus.AUDIT_ONLY, audit.status)
        }
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

    private fun id() = EventId(UUID.randomUUID().toString())

    private fun JsonObject.score(side: String) = getValue("score").jsonObject.getValue(side).jsonPrimitive.int
}
