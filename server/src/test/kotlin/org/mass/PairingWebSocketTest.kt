package org.mass

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.server.testing.testApplication
import org.h2.jdbcx.JdbcDataSource
import org.mass.replication.JdbcPeerJournal
import org.mass.domain.BracketId
import org.mass.domain.BracketOwnership
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SessionLifecycleJournal
import org.mass.domain.SessionState
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
    fun `accepted lifecycle command publishes one state update to authenticated subscribers`() = testApplication {
        val pairingRequests = PairingRequests()
        val sender = approvedDevice(pairingRequests, "ios-lifecycle-sender", "Petrova", "ios")
        val subscriber = approvedDevice(pairingRequests, "android-lifecycle-subscriber", "Ivanov", "android")
        val journal = lifecycleJournal()
        application {
            module(pairingRequests = pairingRequests, lifecycleCommands = RealtimeSessionLifecycleCommands(journal))
        }

        val client = createClient { install(WebSockets) }
        val senderSession = client.webSocketSession("/v1/realtime")
        val subscriberSession = client.webSocketSession("/v1/realtime")
        senderSession.sendHandshake(sender.request.reconnectCredential)
        subscriberSession.sendHandshake(subscriber.request.reconnectCredential)
        senderSession.receiveJson()
        subscriberSession.receiveJson()

        senderSession.send(Frame.Text(lifecycleCommand()))

        assertEquals("session_lifecycle_ack", senderSession.receiveJson().getValue("type").jsonPrimitive.content)
        assertSessionStateUpdate(senderSession.receiveJson())
        assertSessionStateUpdate(subscriberSession.receiveJson())

        senderSession.send(Frame.Text(lifecycleCommand()))

        assertEquals("session_lifecycle_ack", senderSession.receiveJson().getValue("type").jsonPrimitive.content)
        assertEquals(null, withTimeoutOrNull(100) { subscriberSession.incoming.receive() })
    }

    @Test
    fun `rejected lifecycle command does not publish a state update`() = testApplication {
        val pairingRequests = PairingRequests()
        val sender = approvedDevice(pairingRequests, "ios-lifecycle-rejected", "Petrova", "ios")
        val subscriber = approvedDevice(pairingRequests, "android-lifecycle-rejected", "Ivanov", "android")
        application {
            module(pairingRequests = pairingRequests, lifecycleCommands = RealtimeSessionLifecycleCommands(lifecycleJournal()))
        }

        val client = createClient { install(WebSockets) }
        val senderSession = client.webSocketSession("/v1/realtime")
        val subscriberSession = client.webSocketSession("/v1/realtime")
        senderSession.sendHandshake(sender.request.reconnectCredential)
        subscriberSession.sendHandshake(subscriber.request.reconnectCredential)
        senderSession.receiveJson()
        subscriberSession.receiveJson()

        senderSession.send(Frame.Text(lifecycleCommand(eventId = "not-a-uuid")))

        assertEquals("session_lifecycle_rejected", senderSession.receiveJson().getValue("type").jsonPrimitive.content)
        assertEquals(null, withTimeoutOrNull(100) { subscriberSession.incoming.receive() })
    }

    @Test
    fun `authenticated owner lifecycle command updates the projection before its idempotent ACK`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-lifecycle", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val ownerPeerId = "00000000-0000-4000-8000-000000000001"
        val bracketId = "00000000-0000-4000-8000-000000000002"
        val sessionId = "00000000-0000-4000-8000-000000000003"
        val journal = SessionLifecycleJournal(
            BracketOwnership.assign(BracketId(bracketId), PeerId(ownerPeerId)).start(PeerId(ownerPeerId)),
            SessionId(sessionId),
        )
        application {
            module(
                pairingRequests = pairingRequests,
                lifecycleCommands = RealtimeSessionLifecycleCommands(journal),
            )
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        val command = """{"type":"session_lifecycle_command","eventId":"00000000-0000-4000-8000-000000000004","competitionId":"00000000-0000-4000-8000-000000000005","peerId":"$ownerPeerId","courtId":"00000000-0000-4000-8000-000000000006","bracketId":"$bracketId","sessionId":"$sessionId","judgeId":"00000000-0000-4000-8000-000000000007","deviceId":"00000000-0000-4000-8000-000000000008","source":"operator","author":"operator-1","eventType":"session_started","payload":"{}"}"""
        session.send(Frame.Text(command))

        val acknowledgement = session.receiveJson()
        assertEquals("session_lifecycle_ack", acknowledgement.getValue("type").jsonPrimitive.content)
        assertEquals("00000000-0000-4000-8000-000000000004", acknowledgement.getValue("eventId").jsonPrimitive.content)
        assertEquals("running", acknowledgement.getValue("state").jsonPrimitive.content)
        assertSessionStateUpdate(session.receiveJson())
        assertEquals(SessionState.RUNNING, journal.projection().state)
        assertEquals(1, journal.events().size)

        session.send(Frame.Text(command))

        assertEquals(acknowledgement, session.receiveJson())
        assertEquals(1, journal.events().size)
    }

    @Test
    fun `authenticated realtime rejects an invalid lifecycle event ID without changing the session`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-invalid-lifecycle", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val journal = lifecycleJournal()
        application {
            module(pairingRequests = pairingRequests, lifecycleCommands = RealtimeSessionLifecycleCommands(journal))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text(lifecycleCommand(eventId = "not-a-uuid")))

        val rejection = session.receiveJson()
        assertEquals("session_lifecycle_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_lifecycle_command", rejection.getValue("code").jsonPrimitive.content)
        assertEquals(SessionState.PREPARED, journal.projection().state)
        assertEquals(emptyList(), journal.events())
    }

    @Test
    fun `authenticated realtime rejects a foreign lifecycle owner without changing the session`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-foreign-lifecycle", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val journal = lifecycleJournal()
        application {
            module(pairingRequests = pairingRequests, lifecycleCommands = RealtimeSessionLifecycleCommands(journal))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text(lifecycleCommand(peerId = "00000000-0000-4000-8000-000000000009")))

        val rejection = session.receiveJson()
        assertEquals("session_lifecycle_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("lifecycle_command_rejected", rejection.getValue("code").jsonPrimitive.content)
        assertEquals(SessionState.PREPARED, journal.projection().state)
        assertEquals(emptyList(), journal.events())
    }

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
    fun `journal configured realtime endpoint returns typed ordered events after a resync cursor`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-resync", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:realtime-endpoint-resync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        val journal = JdbcPeerJournal("court-1", dataSource)
        RealtimeCommands(journal = journal).accept(command("event-1", sequence = 1))
        val commands = RealtimeCommands(journal = journal)
        commands.accept(command("event-1", sequence = 1))
        commands.accept(command("event-2", sequence = 2))
        application {
            module(pairingRequests = pairingRequests, realtimeCommands = commands)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"resync_request","cursor":"event-1"}"""))

        val response = session.receiveJson()
        assertEquals("resync_response", response.getValue("type").jsonPrimitive.content)
        assertEquals("event-2", response.getValue("cursor").jsonPrimitive.content)
        val events = response.getValue("events").jsonArray
        assertEquals(1, events.size)
        assertEquals("event-2", events[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("court-1", events[0].jsonObject.getValue("ownerPeerId").jsonPrimitive.content)
        assertEquals(2, events[0].jsonObject.getValue("journalSequence").jsonPrimitive.long)
        assertEquals("command", events[0].jsonObject.getValue("command").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `realtime endpoint rejects unknown and malformed resync cursors`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-invalid-resync", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:realtime-endpoint-invalid-resync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        val commands = RealtimeCommands(journal = JdbcPeerJournal("court-1", dataSource))
        commands.accept(command("event-1", sequence = 1))
        application {
            module(pairingRequests = pairingRequests, realtimeCommands = commands)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"resync_request","cursor":"unknown-event"}"""))

        val unknownCursor = session.receiveJson()
        assertEquals("resync_rejected", unknownCursor.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_resync_cursor", unknownCursor.getValue("code").jsonPrimitive.content)
        session.send(Frame.Text("""{"type":"resync_request","cursor":{}}"""))

        val malformedCursor = session.receiveJson()
        assertEquals("resync_rejected", malformedCursor.getValue("type").jsonPrimitive.content)
        assertEquals("invalid_resync_cursor", malformedCursor.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `realtime endpoint rejects resync when no journal is configured`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-no-resync", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        application {
            module(pairingRequests = pairingRequests)
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"resync_request","cursor":null}"""))

        val rejection = session.receiveJson()
        assertEquals("resync_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("resync_unavailable", rejection.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `realtime endpoint rejects resync when the journal is unavailable`() = testApplication {
        val pairingRequests = PairingRequests()
        val pending = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-unavailable-resync", "Petrova", "ios")),
        )
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(pending.request.requestId))
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:realtime-endpoint-unavailable-resync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        val journal = JdbcPeerJournal("court-1", dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP ALL OBJECTS") }
        }
        application {
            module(pairingRequests = pairingRequests, realtimeCommands = RealtimeCommands(journal = journal))
        }

        val session = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        session.sendHandshake(accepted.request.reconnectCredential)
        session.receiveJson()
        session.send(Frame.Text("""{"type":"resync_request","cursor":null}"""))

        val rejection = session.receiveJson()
        assertEquals("resync_rejected", rejection.getValue("type").jsonPrimitive.content)
        assertEquals("journal_unavailable", rejection.getValue("code").jsonPrimitive.content)
    }

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
        session.send(
            Frame.Text(
                """{"type":"command","eventId":"event-after-heartbeat-rejection","sequence":1,"clientTimestamp":"2026-09-01T10:00:00Z","sessionId":"session-1","payload":{"type":"attention"}}""",
            ),
        )

        val acknowledgement = session.receiveJson()
        assertEquals("command_ack", acknowledgement.getValue("type").jsonPrimitive.content)
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

    private fun command(eventId: String, sequence: Long) = RealtimeCommandRequest(
        type = "command",
        eventId = eventId,
        sequence = sequence,
        clientTimestamp = "2026-09-01T10:00:00Z",
        sessionId = "session-1",
        payload = Json.parseToJsonElement("""{"type":"attention"}""").jsonObject,
    )

    private fun lifecycleJournal(): SessionLifecycleJournal {
        val ownerPeerId = PeerId("00000000-0000-4000-8000-000000000001")
        return SessionLifecycleJournal(
            BracketOwnership.assign(BracketId("00000000-0000-4000-8000-000000000002"), ownerPeerId).start(ownerPeerId),
            SessionId("00000000-0000-4000-8000-000000000003"),
        )
    }

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

    private fun assertSessionStateUpdate(message: kotlinx.serialization.json.JsonObject) {
        assertEquals("session_state_updated", message["type"]?.jsonPrimitive?.content, message.toString())
        assertEquals("00000000-0000-4000-8000-000000000003", message.getValue("sessionId").jsonPrimitive.content)
        assertEquals("running", message.getValue("state").jsonPrimitive.content)
    }

    private fun lifecycleCommand(
        eventId: String = "00000000-0000-4000-8000-000000000004",
        peerId: String = "00000000-0000-4000-8000-000000000001",
    ) = """{"type":"session_lifecycle_command","eventId":"$eventId","competitionId":"00000000-0000-4000-8000-000000000005","peerId":"$peerId","courtId":"00000000-0000-4000-8000-000000000006","bracketId":"00000000-0000-4000-8000-000000000002","sessionId":"00000000-0000-4000-8000-000000000003","judgeId":"00000000-0000-4000-8000-000000000007","deviceId":"00000000-0000-4000-8000-000000000008","source":"operator","author":"operator-1","eventType":"session_started","payload":"{}"}"""
}
