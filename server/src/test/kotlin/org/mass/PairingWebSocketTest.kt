package org.mass

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

class PairingWebSocketTest {
    @Test
    fun `authenticated websocket updates the operator device connection projection`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-connection", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()

        assertEquals(
            listOf(OperatorDeviceConnection("ios-connection", "ios", DeviceConnectionState.CONNECTED)),
            pairingRequests.operatorDevices(),
        )

        session.close()
        withTimeout(5_000) {
            while (pairingRequests.operatorDevices().single().connectionState != DeviceConnectionState.DISCONNECTED) {
                delay(10)
            }
        }
    }

    @Test
    fun `authenticated realtime session closes after a missed heartbeat deadline`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-timeout", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests, heartbeatTimeout = Duration.ofMillis(50))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()

        assertEquals(
            "heartbeat_timeout",
            withTimeout(5_000) { session.closeReason.await() }?.message,
        )
    }

    @Test
    fun `valid heartbeat renews the realtime session deadline`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-heartbeat-renewal", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests, heartbeatTimeout = Duration.ofMillis(200))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        delay(100)
        session.send(Frame.Text("""{"type":"heartbeat"}"""))
        assertEquals("heartbeat_ack", session.receiveJson().getValue("type").jsonPrimitive.content)
        delay(150)

        assertTrue(!session.closeReason.isCompleted)
        assertEquals(
            "heartbeat_timeout",
            withTimeout(5_000) { session.closeReason.await() }?.message,
        )
    }

    @Test
    fun `malformed heartbeat does not renew the realtime session deadline`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-malformed-heartbeat", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests, heartbeatTimeout = Duration.ofMillis(200))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        delay(100)
        session.send(Frame.Text("""{"type":"heartbeat","unexpected":true}"""))
        assertEquals("heartbeat_rejected", session.receiveJson().getValue("type").jsonPrimitive.content)

        assertEquals(
            "heartbeat_timeout",
            withTimeout(5_000) { session.closeReason.await() }?.message,
        )
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
    fun `authenticated heartbeat receives a typed acknowledgement`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-heartbeat", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"heartbeat"}"""))

        val acknowledgement = session.receiveJson()
        assertEquals("heartbeat_ack", acknowledgement.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `malformed heartbeat is rejected without closing an authenticated session`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("android-heartbeat", "Ivanov", "android")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"heartbeat","unexpected":true}"""))

        val rejection = session.receiveJson()
        assertEquals("heartbeat_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_heartbeat", rejection.getValue("code").jsonPrimitive.content)
        session.send(Frame.Text(RESYNC_REQUEST))
        assertEquals("resync_response", session.receiveJson().getValue("type").jsonPrimitive.content)
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

        session.send(Frame.Text(RESYNC_REQUEST))
        assertEquals("resync_response", session.receiveJson().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a message after a ping is processed`() = testApplication {
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
        session.send(Frame.Text(RESYNC_REQUEST))
        assertEquals("resync_response", session.receiveJson().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `revocation closes the authenticated session at once`() = testApplication {
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

        assertEquals("credential_revoked", withTimeout(5_000) { session.closeReason.await() }?.message)
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

    private fun approvedDevice(
        pairingRequests: PairingRequests,
        deviceId: String,
        surname: String,
        platform: String,
    ): PairingApproval.Accepted {
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand(deviceId, surname, platform)),
        )
        return assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
    }

    private companion object {
        const val RESYNC_REQUEST =
            """{"type":"resync_request","requestId":"1e2d3c4b-5a69-4788-9a0b-c1d2e3f4a5b6","cursor":null}"""
    }
}
