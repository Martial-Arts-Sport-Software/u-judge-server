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
    fun `approved credential receives the original ACK when retrying a command`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-ack", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()

        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-1","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )
        val firstAck = session.receiveJson()
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-1","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )
        val retryAck = session.receiveJson()

        assertEquals("command_ack", firstAck.getValue("type").jsonPrimitive.content)
        assertEquals("event-1", firstAck.getValue("eventId").jsonPrimitive.content)
        assertEquals(firstAck, retryAck)
    }

    @Test
    fun `revoked credential cannot submit a command after handshake`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("android-revoked", "Ivanov", "android")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        pairingRequests.revoke(accepted.request.requestId)

        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-revoked","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )

        val rejection = session.receiveJson()
        assertEquals("command_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_reconnect_credential", rejection.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `oversized command is rejected without closing an approved session`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-large", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("x".repeat(4_097)))

        val rejection = session.receiveJson()
        assertEquals("command_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("command_too_large", rejection.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `command with a non-string payload type is rejected`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-invalid", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-invalid","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":{}}}""",
            ),
        )

        val rejection = session.receiveJson()
        assertEquals("command_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_command", rejection.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `command with a boolean payload type is rejected`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-boolean", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-boolean","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":true}}""",
            ),
        )

        val rejection = session.receiveJson()
        assertEquals("command_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_command", rejection.getValue("code").jsonPrimitive.content)
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
