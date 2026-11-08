package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.mass.bout.BoutCommand
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStateAction
import org.mass.bout.JdbcBoutEventJournal
import org.mass.bout.KerugiBoutService
import org.mass.domain.EventId
import org.mass.domain.PeerId
import org.mass.persistence.JdbcDomainEventStore
import org.mass.performance.TechnicalPerformanceService
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * I7 security check (`NFR-006`, `NFR-007`): the LAN surface of the server has no anonymous write. The only anonymous
 * request that stores anything is a bounded pairing request, which creates a pending entry the operator must approve on
 * the desktop; every judge input needs the handshake of an approved, unrevoked device; operator actions are in-process.
 */
class WriteSurfaceTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:write-surface-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }

    @Test
    fun `the module exposes exactly the reviewed routes and no other write`() = testApplication {
        lateinit var routes: List<String>
        application {
            module()
            routes = checkNotNull(pluginOrNull(RoutingRoot)).getAllRoutes().map(::describe).sorted()
        }
        startApplication()
        assertEquals(
            listOf(
                "GET /",
                "GET /v1/health",
                "GET /v1/metadata",
                "GET /v1/pairing-status/{requestId}",
                // The WebSocket upgrade; every frame after it needs an approved credential (see below).
                "GET /v1/realtime",
                // Creates a pending request only; the operator approves it on the desktop (ADR-006).
                "POST /v1/pairing-requests",
            ),
            routes,
        )
    }

    @Test
    fun `write verbs on every path and on former or guessable endpoints change nothing`() = testApplication {
        val pairing = PairingRequests()
        val store = JdbcDomainEventStore(dataSource)
        application { module(pairingRequests = pairing, realtime = hub(store)) }
        val paths = listOf(
            "/", "/v1/health", "/v1/metadata", "/v1/pairing-status/any", "/v1/realtime", "/score", "/v1/score",
            "/v1/sessions", "/v1/devices", "/v1/devices/any/revoke", "/v1/pairing-requests/any/approve", "/v1/backups",
        )
        val verbs = listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
        for (path in paths) {
            for (verb in verbs) {
                val response = client.request(path) {
                    method = verb
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"deviceId":"x","surname":"y","platform":"android"}""")
                }
                assertTrue(response.status in setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed), "$verb $path: ${response.status}")
            }
        }
        assertEquals(emptyList(), pairing.pending())
        assertEquals(0, eventCount())
    }

    @Test
    fun `an oversized or malformed pairing request is refused before the pairing service`() = testApplication {
        val pairing = PairingRequests()
        application { module(pairingRequests = pairing) }

        val oversized = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"android-1","surname":"${"Я".repeat(3_000)}","platform":"android"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("pairing_request_too_large", code(oversized.bodyAsText()))

        for (body in listOf("""{"deviceId":""", "[]", """{"deviceId":"a","surname":"b","platform":"android","role":"operator"}""")) {
            val malformed = client.post("/v1/pairing-requests") {
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, malformed.status, body)
            assertEquals("invalid_pairing_request", code(malformed.bodyAsText()))
        }
        assertEquals(emptyList(), pairing.pending())

        val valid = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"android-1","surname":"Иванов","platform":"android"}""")
        }
        assertEquals(HttpStatusCode.Accepted, valid.status)
        assertEquals(1, pairing.pending().size)
    }

    @Test
    fun `judge input without an approved handshake or in an oversized frame is never journaled`() = testApplication {
        val pairing = PairingRequests()
        val store = JdbcDomainEventStore(dataSource)
        val hub = hub(store)
        application { module(pairingRequests = pairing, realtime = hub) }
        val credential = approve(pairing, "android-1")
        val sessionId = assertIs<BoutOutcome.Applied>(hub.operator("Оператор", BoutCommand.StartBout(EventId.new(), testBoutSpec(listOf("android-1", "android-2"))))).sessionId
        hub.operator("Оператор", BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.START))
        val events = eventCount()
        val session = checkNotNull(hub.snapshotBody("android-1")).jsonObject
        val tap = scoreCommandFor(session, EventId.new().value, Instant.now()).toString()
        val ws = createClient { install(WebSockets) }

        // A score command in place of the handshake, and a handshake with an unknown credential.
        for (first in listOf(tap, """{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"guess"}""")) {
            val socket = ws.webSocketSession("/v1/realtime")
            socket.send(Frame.Text(first))
            assertEquals("handshake_rejected", Json.parseToJsonElement((socket.incoming.receive() as Frame.Text).readText()).jsonObject.getValue("type").jsonPrimitive.content)
            // The server closed the socket after the rejection; a command sent anyway is never read.
            runCatching { socket.send(Frame.Text(tap)) }
            drain(socket)
        }

        // An approved device that sends a frame above the frame limit loses the socket before the frame is read.
        val socket = ws.webSocketSession("/v1/realtime")
        socket.send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}"""))
        assertEquals("handshake_accepted", Json.parseToJsonElement((socket.incoming.receive() as Frame.Text).readText()).jsonObject.getValue("type").jsonPrimitive.content)
        socket.send(Frame.Text(tap.dropLast(1) + ""","padding":"${"x".repeat(MAX_REALTIME_FRAME_BYTES.toInt())}"}"""))
        drain(socket)
        assertEquals(CloseReason.Codes.TOO_BIG, withTimeout(5_000) { socket.closeReason.await() }?.knownReason)

        // Revoked: the same credential no longer opens a session.
        pairing.revokeAll()
        val revoked = ws.webSocketSession("/v1/realtime")
        revoked.send(Frame.Text("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}"""))
        assertEquals("invalid_reconnect_credential", code((revoked.incoming.receive() as Frame.Text).readText()))

        assertEquals(events, eventCount())
        assertEquals(0, hub.bouts.current()!!.score().blue)
    }

    private fun eventCount(): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM domain_events").use { rows -> rows.next(); rows.getInt(1) } }
    }

    private fun hub(store: JdbcDomainEventStore): RealtimeHub {
        val journal = JdbcBoutEventJournal(store)
        return RealtimeHub(KerugiBoutService(journal, peerId), TechnicalPerformanceService(journal, peerId))
    }

    private fun approve(pairing: PairingRequests, deviceId: String): String {
        val pending = assertIs<PairingSubmission.Pending>(pairing.submit(PairingRequestCommand(deviceId, "Судья", "android")))
        return assertIs<PairingApproval.Accepted>(pairing.approve(pending.request.requestId)).request.reconnectCredential
    }

    /** Reads the socket until the server closes it; a cancelled channel is a closed socket too. */
    private suspend fun drain(socket: io.ktor.websocket.WebSocketSession) = withTimeout(5_000) {
        runCatching { socket.incoming.consumeEach { } }
    }

    private fun code(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("code").jsonPrimitive.content

    /** `METHOD /path` of a leaf route, with the path segments of its parents and without header selectors. */
    private fun describe(route: RoutingNode): String {
        val chain = generateSequence(route) { it.parent }.toList().reversed()
        val method = chain.mapNotNull { (it.selector as? HttpMethodRouteSelector)?.method?.value }.single()
        val path = chain.map { it.selector.toString() }
            .filter { it.isNotEmpty() && !it.startsWith("(") && it != "/" && it != "<slash>" }
            .joinToString("/", prefix = "/")
        return "$method $path"
    }
}
