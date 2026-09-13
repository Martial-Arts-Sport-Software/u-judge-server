package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
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
    fun `accepted Kerugi score command publishes one projection update to authenticated subscribers`() = testApplication {
        val pairingRequests = PairingRequests()
        val sender = approvedDevice(pairingRequests, "ios-kerugi-sender", "Petrova", "ios")
        val subscriber = approvedDevice(pairingRequests, "android-kerugi-subscriber", "Ivanov", "android")
        application {
            module(pairingRequests = pairingRequests, kerugiScoreCommands = RealtimeKerugiScoreCommands(journal()))
        }

        val client = createClient { install(WebSockets) }
        val senderSession = client.webSocketSession("/v1/realtime")
        val subscriberSession = client.webSocketSession("/v1/realtime")
        senderSession.sendHandshake(sender.request.reconnectCredential)
        subscriberSession.sendHandshake(subscriber.request.reconnectCredential)
        senderSession.receiveJson()
        subscriberSession.receiveJson()

        senderSession.send(Frame.Text(command(10, 7, "HEAD", 0)))
        assertEquals("kerugi_score_ack", senderSession.receiveJson().getValue("type").jsonPrimitive.content)
        assertScoreUpdate(senderSession.receiveJson(), 0, 0)
        assertScoreUpdate(subscriberSession.receiveJson(), 0, 0)

        senderSession.send(Frame.Text(command(11, 8, "BODY", 500)))
        val acknowledgement = senderSession.receiveJson()
        assertEquals("kerugi_score_ack", acknowledgement.getValue("type").jsonPrimitive.content)
        assertScoreUpdate(senderSession.receiveJson(), 1, 0)
        assertScoreUpdate(subscriberSession.receiveJson(), 1, 0)

        senderSession.send(Frame.Text(command(11, 8, "BODY", 500)))
        assertEquals(acknowledgement, senderSession.receiveJson())
        assertEquals(null, withTimeoutOrNull(100) { subscriberSession.incoming.receive() })

        senderSession.send(Frame.Text(command(12, 9, "HEAD", 750)))
        assertEquals("kerugi_score_rejected", senderSession.receiveJson().getValue("type").jsonPrimitive.content)
        assertEquals(null, withTimeoutOrNull(100) { subscriberSession.incoming.receive() })
    }

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
        assertScoreUpdate(session.receiveJson(), 0, 0)
        session.send(Frame.Text(command(11, 8, "BODY", 500)))
        val acknowledgement = session.receiveJson()
        assertEquals("kerugi_score_ack", acknowledgement.getValue("type").jsonPrimitive.content)
        assertEquals("1", acknowledgement.getValue("blueScore").jsonPrimitive.content)
        assertScoreUpdate(session.receiveJson(), 1, 0)
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

    @Test
    fun `operator action acknowledges after persistence and publishes a new score projection`() = testApplication {
        val pairingRequests = PairingRequests()
        val sender = approvedDevice(pairingRequests, "ios-operator", "Petrova", "ios")
        val subscriber = approvedDevice(pairingRequests, "android-watcher", "Ivanov", "android")
        val journal = journal()
        application {
            module(
                pairingRequests = pairingRequests,
                kerugiOperatorActionCommands = RealtimeKerugiOperatorActionCommands(journal),
            )
        }

        val client = createClient { install(WebSockets) }
        val senderSession = client.webSocketSession("/v1/realtime")
        val subscriberSession = client.webSocketSession("/v1/realtime")
        senderSession.sendHandshake(sender.request.reconnectCredential)
        subscriberSession.sendHandshake(subscriber.request.reconnectCredential)
        senderSession.receiveJson()
        subscriberSession.receiveJson()

        senderSession.send(Frame.Text(operatorAction(20, "GAMJEOM", "BLUE", 1)))
        val acknowledgement = senderSession.receiveJson()
        assertEquals("kerugi_operator_action_ack", acknowledgement.getValue("type").jsonPrimitive.content)
        assertEquals("0", acknowledgement.getValue("blueScore").jsonPrimitive.content)
        assertEquals("1", acknowledgement.getValue("redScore").jsonPrimitive.content)
        assertScoreUpdate(senderSession.receiveJson(), 0, 1)
        assertScoreUpdate(subscriberSession.receiveJson(), 0, 1)

        senderSession.send(Frame.Text(operatorAction(20, "GAMJEOM", "BLUE", 1)))
        assertEquals(acknowledgement, senderSession.receiveJson())
        assertEquals(null, withTimeoutOrNull(100) { subscriberSession.incoming.receive() })

        senderSession.send(Frame.Text(operatorAction(21, "THROW", "BLUE", 0)))
        assertEquals("kerugi_operator_action_rejected", senderSession.receiveJson().getValue("type").jsonPrimitive.content)
        assertEquals(null, withTimeoutOrNull(100) { subscriberSession.incoming.receive() })
    }

    private fun journal(): KerugiScoreJournal {
        val peerId = PeerId("00000000-0000-4000-8000-000000000001")
        return KerugiScoreJournal(
            BracketOwnership.assign(BracketId("00000000-0000-4000-8000-000000000002"), peerId).start(peerId),
            SessionId("00000000-0000-4000-8000-000000000003"),
            KerugiScoringConfiguration(setOf(judgeId(7), judgeId(8)), 2, Duration.ofSeconds(1)),
        )
    }

    private fun approvedDevice(
        pairingRequests: PairingRequests,
        deviceId: String = "ios-kerugi",
        surname: String = "Petrova",
        platform: String = "ios",
    ): PairingApproval.Accepted {
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand(deviceId, surname, platform)),
        )
        return assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
    }

    private fun command(event: Int, judge: Int, area: String, milliseconds: Long) =
        """{"type":"kerugi_score_command","eventId":"00000000-0000-4000-8000-${event.toString().padStart(12, '0')}","competitionId":"00000000-0000-4000-8000-000000000004","peerId":"00000000-0000-4000-8000-000000000001","courtId":"00000000-0000-4000-8000-000000000005","bracketId":"00000000-0000-4000-8000-000000000002","sessionId":"00000000-0000-4000-8000-000000000003","judgeId":"00000000-0000-4000-8000-${judge.toString().padStart(12, '0')}","deviceId":"00000000-0000-4000-8000-000000000006","source":"judge","author":"judge-$judge","competitor":"BLUE","area":"$area","occurredAt":"2026-09-12T12:00:00.${milliseconds.toString().padStart(3, '0')}Z"}"""

    private fun operatorAction(event: Int, action: String, competitor: String, points: Int) =
        """{"type":"kerugi_operator_action_command","eventId":"00000000-0000-4000-8000-${event.toString().padStart(12, '0')}","competitionId":"00000000-0000-4000-8000-000000000004","peerId":"00000000-0000-4000-8000-000000000001","courtId":"00000000-0000-4000-8000-000000000005","bracketId":"00000000-0000-4000-8000-000000000002","sessionId":"00000000-0000-4000-8000-000000000003","judgeId":"00000000-0000-4000-8000-000000000007","deviceId":"00000000-0000-4000-8000-000000000006","source":"operator","author":"operator","action":"$action","competitor":"$competitor","points":$points}"""

    private suspend fun io.ktor.websocket.WebSocketSession.receiveJson() = Json.parseToJsonElement(
        (incoming.receive() as Frame.Text).readText(),
    ).jsonObject

    private suspend fun io.ktor.websocket.WebSocketSession.sendHandshake(reconnectCredential: String) {
        send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$reconnectCredential"}"""))
    }

    private fun assertScoreUpdate(message: kotlinx.serialization.json.JsonObject, blueScore: Int, redScore: Int) {
        assertEquals("kerugi_score_updated", message.getValue("type").jsonPrimitive.content)
        assertEquals("00000000-0000-4000-8000-000000000003", message.getValue("sessionId").jsonPrimitive.content)
        assertEquals(blueScore.toString(), message.getValue("blueScore").jsonPrimitive.content)
        assertEquals(redScore.toString(), message.getValue("redScore").jsonPrimitive.content)
    }

    private fun judgeId(number: Int) = JudgeId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
