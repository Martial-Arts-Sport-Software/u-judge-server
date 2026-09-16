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
import org.mass.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KerugiResultWebSocketTest {
    @Test fun `authenticated result command acknowledges and publishes only new decisions`() = testApplication {
        val requests = PairingRequests()
        val sender = approved(requests, "result-sender")
        val watcher = approved(requests, "result-watcher")
        application { module(pairingRequests = requests, kerugiResultCommands = RealtimeKerugiResultCommands(journal())) }
        val client = createClient { install(WebSockets) }
        val senderSocket = client.webSocketSession("/v1/realtime")
        val watcherSocket = client.webSocketSession("/v1/realtime")
        senderSocket.handshake(sender.request.reconnectCredential); watcherSocket.handshake(watcher.request.reconnectCredential)
        senderSocket.receiveJson(); watcherSocket.receiveJson()
        senderSocket.send(Frame.Text(command(4, "RED", "GOLDEN_ROUND")))
        assertEquals("kerugi_result_ack", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
        assertUpdate(senderSocket.receiveJson()); assertUpdate(watcherSocket.receiveJson())
        senderSocket.send(Frame.Text(command(4, "RED", "GOLDEN_ROUND")))
        assertEquals("kerugi_result_ack", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
        assertEquals(null, withTimeoutOrNull(100) { watcherSocket.incoming.receive() })
        senderSocket.send(Frame.Text(command(5, "BLUE", "FINAL_SCORE", peer = "00000000-0000-4000-8000-000000000009")))
        assertEquals("kerugi_result_rejected", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
    }
    private fun journal(): KerugiResultJournal {
        val peer = PeerId("00000000-0000-4000-8000-000000000001")
        return KerugiResultJournal(BracketOwnership.assign(BracketId("00000000-0000-4000-8000-000000000002"), peer).start(peer), SessionId("00000000-0000-4000-8000-000000000003"))
    }
    private fun approved(requests: PairingRequests, id: String) = assertIs<PairingApproval.Accepted>(requests.approve(assertIs<PairingSubmission.Pending>(requests.submit(PairingRequestCommand(id, "Operator", "ios"))).request.requestId))
    private fun command(event: Int, winner: String, reason: String, peer: String = "00000000-0000-4000-8000-000000000001") = """{"type":"kerugi_result_command","eventId":"00000000-0000-4000-8000-${event.toString().padStart(12, '0')}","sequence":$event,"clientTimestamp":"2026-09-14T12:00:00Z","competitionId":"00000000-0000-4000-8000-000000000004","peerId":"$peer","courtId":"00000000-0000-4000-8000-000000000005","bracketId":"00000000-0000-4000-8000-000000000002","sessionId":"00000000-0000-4000-8000-000000000003","judgeId":"00000000-0000-4000-8000-000000000006","deviceId":"00000000-0000-4000-8000-000000000007","source":"operator","author":"operator","winner":"$winner","reason":"$reason"}"""
    private suspend fun io.ktor.websocket.WebSocketSession.handshake(credential: String) { send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}""")) }
    private suspend fun io.ktor.websocket.WebSocketSession.receiveJson() = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
    private fun assertUpdate(message: kotlinx.serialization.json.JsonObject) {
        assertEquals("kerugi_result_updated", message.getValue("type").jsonPrimitive.content)
        assertEquals("red", message.getValue("winner").jsonPrimitive.content)
        assertEquals("golden_round", message.getValue("reason").jsonPrimitive.content)
    }
}
