package org.mass

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerLogTest {
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(ServerLog.LOGGER_NAME) as Logger

    @BeforeTest
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `realtime log correlates by device and event IDs without surname credential or payload`() = testApplication {
        val pairingRequests = PairingRequests()
        application { module(pairingRequests = pairingRequests) }

        val requestId = Json.parseToJsonElement(
            client.post("/v1/pairing-requests") {
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"device-log-1","surname":"Ivanova","platform":"ios"}""")
            }.bodyAsText(),
        ).jsonObject.getValue("requestId").jsonPrimitive.content
        val credential = (pairingRequests.approve(requestId) as PairingApproval.Accepted).request.reconnectCredential
        val socket = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        socket.send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}"""))
        (socket.incoming.receive() as Frame.Text).readText()
        socket.send(
            Frame.Text(
                """{"type":"command","eventId":"event-log-1","sequence":1,"clientTimestamp":"2026-09-26T10:00:00Z",""" +
                    """"sessionId":"session-1","payload":{"type":"kerugi_score","secret":"payload-marker"}}""",
            ),
        )
        (socket.incoming.receive() as Frame.Text).readText()
        socket.close()
        val events = withTimeout(5_000) {
            while (snapshot().none { it.message == "realtime_disconnected" }) delay(10)
            snapshot()
        }

        val acknowledged = events.single { it.message == "command_acknowledged" }
        assertEquals(
            mapOf("deviceId" to "device-log-1", "eventId" to "event-log-1"),
            acknowledged.keyValuePairs.associate { it.key to it.value.toString() },
        )
        val rendered = events.joinToString("\n") { event ->
            event.formattedMessage + event.keyValuePairs.orEmpty().joinToString { "${it.key}=${it.value}" }
        }
        assertTrue("realtime_connected" in rendered)
        listOf("Ivanova", credential, "payload-marker").forEach { secret -> assertTrue(secret !in rendered, secret) }
    }

    private fun snapshot(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
}
