package org.mass

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PairingWebSocketTest {
    @Test
    fun `approved credential receives an accepted handshake`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-1", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)

        assertEquals(
            "handshake_accepted",
            session.receiveJson().getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `unknown credential receives a typed rejected handshake`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake("unknown-credential")

        val response = session.receiveJson()
        assertEquals("handshake_rejected", response.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_reconnect_credential", response.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `unsupported protocol version receives a typed rejected handshake`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-2", "Sidorova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.send(
            Frame.Text(
                """{"type":"handshake","protocolVersion":"2.0","reconnectCredential":"${accepted.request.reconnectCredential}"}""",
            ),
        )

        val response = session.receiveJson()
        assertEquals("handshake_rejected", response.getValue("type").jsonPrimitive.content)
        assertEquals("unsupported_protocol_version", response.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `revoked credential receives a typed rejected handshake`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("android-2", "Ivanov", "android")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        pairingRequests.revoke(accepted.request.requestId)
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)

        val response = session.receiveJson()
        assertEquals("handshake_rejected", response.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_reconnect_credential", response.getValue("code").jsonPrimitive.content)
    }

    private suspend fun WebSocketSession.sendHandshake(reconnectCredential: String) {
        send(
            Frame.Text(
                """{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$reconnectCredential"}""",
            ),
        )
    }

    private suspend fun WebSocketSession.receiveJson() = Json.parseToJsonElement(
        (incoming.receive() as Frame.Text).readText(),
    ).jsonObject
}
