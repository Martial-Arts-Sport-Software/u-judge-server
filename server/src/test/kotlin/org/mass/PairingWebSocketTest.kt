package org.mass

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.server.testing.testApplication
import org.h2.jdbcx.JdbcDataSource
import org.mass.replication.JdbcPeerJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class PairingWebSocketTest {
    @Test
    fun `journal configured realtime endpoint acknowledges a command after it is persisted`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-durable", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:realtime-endpoint-journal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        val journal = JdbcPeerJournal("court-1", dataSource)
        application {
            module(pairingRequests = pairingRequests, realtimeCommands = RealtimeCommands(journal = journal))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-durable","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )

        val acknowledgement = session.receiveJson()
        assertEquals("command_ack", acknowledgement.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("event-durable"), journal.eventIds)
    }

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
    fun `clock sync echoes the client timestamp and rejects an invalid timestamp without closing the session`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-clock", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"clock_sync","clientSendTimestamp":"not-a-timestamp"}"""))

        val rejection = session.receiveJson()
        assertEquals("clock_sync_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_clock_sync_timestamp", rejection.getValue("code").jsonPrimitive.content)
        session.send(Frame.Text("""{"type":"clock_sync","clientSendTimestamp":123}"""))

        val malformedTimestampRejection = session.receiveJson()
        assertEquals("clock_sync_rejected", malformedTimestampRejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_clock_sync_timestamp", malformedTimestampRejection.getValue("code").jsonPrimitive.content)

        session.send(Frame.Text("""{"type":"clock_sync","clientSendTimestamp":"2026-09-01T10:00:00Z"}"""))

        val response = session.receiveJson()
        assertEquals("clock_sync_response", response.getValue("type").jsonPrimitive.content)
        assertEquals("2026-09-01T10:00:00Z", response.getValue("clientSendTimestamp").jsonPrimitive.content)
        val serverReceiveTimestampText = response.getValue("serverReceiveTimestamp").jsonPrimitive.content
        val serverSendTimestampText = response.getValue("serverSendTimestamp").jsonPrimitive.content
        assertTrue(serverReceiveTimestampText.endsWith("Z"))
        assertTrue(serverSendTimestampText.endsWith("Z"))
        val serverReceiveTimestamp = Instant.parse(serverReceiveTimestampText)
        val serverSendTimestamp = Instant.parse(serverSendTimestampText)
        assertTrue(!serverSendTimestamp.isBefore(serverReceiveTimestamp))

        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-after-clock-rejection","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )

        val acknowledgement = session.receiveJson()
        assertEquals("command_ack", acknowledgement.getValue("type").jsonPrimitive.content)
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
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-1","sequence":2,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )
        val conflict = session.receiveJson()

        assertEquals("command_ack", firstAck.getValue("type").jsonPrimitive.content)
        assertEquals("event-1", firstAck.getValue("eventId").jsonPrimitive.content)
        assertEquals(firstAck, retryAck)
        assertEquals("command_rejected", conflict.getValue("type").jsonPrimitive.content)
        assertEquals("event_id_conflict", conflict.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `command after a ping is processed`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-ping", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Ping(byteArrayOf()))
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-after-ping","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )

        val acknowledgement = session.receiveJson()
        assertEquals("command_ack", acknowledgement.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `new command is rejected when the receipt limit is reached`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-limit", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests, realtimeCommands = RealtimeCommands(maximumReceipts = 1))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-first","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )
        session.receiveJson()
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-second","sequence":2,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )

        val rejection = session.receiveJson()
        assertEquals("command_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("command_receipt_limit_reached", rejection.getValue("code").jsonPrimitive.content)
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
