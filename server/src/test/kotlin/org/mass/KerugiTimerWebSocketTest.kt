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

class KerugiTimerWebSocketTest {
    @Test fun `authenticated timer command acknowledges and publishes only new events`() = testApplication {
        val requests = PairingRequests()
        val sender = approved(requests, "timer-sender")
        val watcher = approved(requests, "timer-watcher")
        application { module(pairingRequests = requests, kerugiTimerCommands = RealtimeKerugiTimerCommands(journal())) }
        val client = createClient { install(WebSockets) }
        val senderSocket = client.webSocketSession("/v1/realtime")
        val watcherSocket = client.webSocketSession("/v1/realtime")
        senderSocket.handshake(sender.request.reconnectCredential); watcherSocket.handshake(watcher.request.reconnectCredential)
        senderSocket.receiveJson(); watcherSocket.receiveJson()
        senderSocket.send(Frame.Text(command(4, "START")))
        assertEquals("kerugi_timer_ack", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
        assertUpdate(senderSocket.receiveJson(), "running"); assertUpdate(watcherSocket.receiveJson(), "running")
        senderSocket.send(Frame.Text(command(5, "START_BREAK")))
        assertEquals("kerugi_timer_ack", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
        assertUpdate(senderSocket.receiveJson(), "round_break"); assertUpdate(watcherSocket.receiveJson(), "round_break")
        senderSocket.send(Frame.Text(command(5, "START_BREAK")))
        assertEquals("kerugi_timer_ack", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
        assertEquals(null, withTimeoutOrNull(100) { watcherSocket.incoming.receive() })
        senderSocket.send(Frame.Text(command(6, "END_BREAK", peer = "00000000-0000-4000-8000-000000000009")))
        assertEquals("kerugi_timer_rejected", senderSocket.receiveJson().getValue("type").jsonPrimitive.content)
    }
    private fun journal(): KerugiTimerJournal {
        val peer = PeerId("00000000-0000-4000-8000-000000000001")
        return KerugiTimerJournal(BracketOwnership.assign(BracketId("00000000-0000-4000-8000-000000000002"), peer).start(peer), SessionId("00000000-0000-4000-8000-000000000003"))
    }
    private fun approved(requests: PairingRequests, id: String) = assertIs<PairingApproval.Accepted>(requests.approve(assertIs<PairingSubmission.Pending>(requests.submit(PairingRequestCommand(id, "Operator", "ios"))).request.requestId))
    private fun command(event: Int, action: String, peer: String = "00000000-0000-4000-8000-000000000001") = """{"type":"kerugi_timer_command","eventId":"00000000-0000-4000-8000-${event.toString().padStart(12, '0')}","sequence":$event,"clientTimestamp":"2026-09-13T12:00:00Z","competitionId":"00000000-0000-4000-8000-000000000004","peerId":"$peer","courtId":"00000000-0000-4000-8000-000000000005","bracketId":"00000000-0000-4000-8000-000000000002","sessionId":"00000000-0000-4000-8000-000000000003","judgeId":"00000000-0000-4000-8000-000000000006","deviceId":"00000000-0000-4000-8000-000000000007","source":"operator","author":"operator","action":"$action"}"""
    private suspend fun io.ktor.websocket.WebSocketSession.handshake(credential: String) { send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}""")) }
    private suspend fun io.ktor.websocket.WebSocketSession.receiveJson() = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
    private fun assertUpdate(message: kotlinx.serialization.json.JsonObject, state: String) { assertEquals("kerugi_timer_updated", message.getValue("type").jsonPrimitive.content); assertEquals(state, message.getValue("state").jsonPrimitive.content) }
}
