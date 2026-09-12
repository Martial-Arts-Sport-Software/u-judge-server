package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mass.domain.BracketId
import org.mass.domain.BracketOwnership
import org.mass.domain.JudgeId
import org.mass.domain.KerugiScoreJournal
import org.mass.domain.KerugiScoringConfiguration
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KerugiWebSocketTest {
    @Test
    fun `authenticated judges receive an idempotent Kerugi ACK after the score projection is applied`() = testApplication {
        val pairingRequests = PairingRequests()
        val accepted = approvedDevice(pairingRequests)
        val journal = journal()
        application {
            module(pairingRequests = pairingRequests, kerugiScoreCommands = RealtimeKerugiScoreCommands(journal))
        }
        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"${accepted.request.reconnectCredential}"}"""))
        session.receiveJson()

        session.send(Frame.Text(command(10, 7, "HEAD", 0)))
        assertEquals("0", session.receiveJson().getValue("blueScore").jsonPrimitive.content)
        session.send(Frame.Text(command(11, 8, "BODY", 500)))
        val acknowledgement = session.receiveJson()
        assertEquals("kerugi_score_ack", acknowledgement.getValue("type").jsonPrimitive.content)
        assertEquals("1", acknowledgement.getValue("blueScore").jsonPrimitive.content)
        session.send(Frame.Text(command(11, 8, "BODY", 500)))
        assertEquals(acknowledgement, session.receiveJson())

        assertEquals(2, journal.events().size)
        assertEquals(1, journal.projection().blueScore)
    }

    @Test
    fun `realtime rejects a score candidate from a judge outside the configured composition`() = testApplication {
        val pairingRequests = PairingRequests()
        val accepted = approvedDevice(pairingRequests)
        val journal = journal()
        application {
            module(pairingRequests = pairingRequests, kerugiScoreCommands = RealtimeKerugiScoreCommands(journal))
        }
        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"${accepted.request.reconnectCredential}"}"""))
        session.receiveJson()

        session.send(Frame.Text(command(10, 9, "HEAD", 0)))

        val rejection = session.receiveJson()
        assertEquals("kerugi_score_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("kerugi_score_command_rejected", rejection.getValue("code").jsonPrimitive.content)
        assertEquals(emptyList(), journal.events())
    }

    private fun journal(): KerugiScoreJournal {
        val peerId = PeerId("00000000-0000-4000-8000-000000000001")
        return KerugiScoreJournal(
            BracketOwnership.assign(BracketId("00000000-0000-4000-8000-000000000002"), peerId).start(peerId),
            SessionId("00000000-0000-4000-8000-000000000003"),
            KerugiScoringConfiguration(setOf(judgeId(7), judgeId(8)), 2, Duration.ofSeconds(1)),
        )
    }

    private fun approvedDevice(pairingRequests: PairingRequests): PairingApproval.Accepted {
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-kerugi", "Petrova", "ios")),
        )
        return assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
    }

    private fun command(event: Int, judge: Int, area: String, milliseconds: Long) =
        """{"type":"kerugi_score_command","eventId":"00000000-0000-4000-8000-${event.toString().padStart(12, '0')}","competitionId":"00000000-0000-4000-8000-000000000004","peerId":"00000000-0000-4000-8000-000000000001","courtId":"00000000-0000-4000-8000-000000000005","bracketId":"00000000-0000-4000-8000-000000000002","sessionId":"00000000-0000-4000-8000-000000000003","judgeId":"00000000-0000-4000-8000-${judge.toString().padStart(12, '0')}","deviceId":"00000000-0000-4000-8000-000000000006","source":"judge","author":"judge-$judge","competitor":"BLUE","area":"$area","occurredAt":"2026-09-12T12:00:00.${milliseconds.toString().padStart(3, '0')}Z"}"""

    private suspend fun io.ktor.websocket.WebSocketSession.receiveJson() = Json.parseToJsonElement(
        (incoming.receive() as Frame.Text).readText(),
    ).jsonObject

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
