package org.mass

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCorner
import org.mass.bout.BoutStartSpec
import org.mass.bout.RosterJudge
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import java.time.Instant
import java.util.UUID

/**
 * A paired judge phone speaking the ADR-009 contract over a real WebSocket: handshake, resync, taps from the snapshot's
 * audit context and a routing reader that keeps pushes apart from replies. Used by the bout acceptance tests; the fault
 * test points [endpoint] at a TCP proxy and gives up on a reply after [replyTimeoutMillis].
 */
class JudgeSimulator(
    private val client: HttpClient,
    private val credential: String,
    val deviceId: String,
    private val endpoint: String = "/v1/realtime",
    private val replyTimeoutMillis: Long = 5_000,
) {
    private lateinit var socket: WebSocketSession
    private val buffered = mutableListOf<JsonObject>()
    private var sequence = 0L

    /** The last applied snapshot body (`session`) and cursor, as the client keeps them. */
    var session: JsonObject? = null
        private set
    var cursor: Long? = null
        private set

    suspend fun connect(): JudgeSimulator {
        socket = client.webSocketSession(endpoint)
        send("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}""")
        check(receive().string("type") == "handshake_accepted")
        return this
    }

    /** Sends `resync_request` with the last cursor and applies the response; the socket is subscribed afterwards. */
    suspend fun resync(): JsonObject {
        val requestId = UUID.randomUUID().toString()
        send(buildJsonObject {
            put("type", "resync_request")
            put("requestId", requestId)
            put("cursor", cursor?.let(::JsonPrimitive) ?: JsonNull)
        }.toString())
        val response = next { it.string("type") == "resync_response" && it.string("requestId") == requestId }
        apply(response)
        return response
    }

    /** A tap built from the current snapshot, as the client stores it in the outbox. */
    fun tap(competitor: String, area: String, at: Instant, eventId: String = UUID.randomUUID().toString()): JsonObject {
        val current = requireNotNull(session) { "No session to judge" }
        val seat = current.getValue("seat").jsonObject
        return buildJsonObject {
            put("type", "kerugi_score_command")
            put("eventId", eventId)
            put("sequence", sequence++)
            put("clientTimestamp", at.toString())
            listOf("competitionId", "peerId", "courtId", "bracketId", "sessionId").forEach { put(it, current.string(it)) }
            put("judgeId", seat.string("judgeId"))
            put("deviceId", seat.string("deviceId"))
            put("competitor", competitor)
            put("area", area)
        }
    }

    /** A `tanbon_score_command` built from the current snapshot; [competitor] is null for `CROSS` (ADR-011 §3). */
    fun tanbon(competitor: String?, area: String, at: Instant, eventId: String = UUID.randomUUID().toString()): JsonObject =
        JsonObject(tap(competitor ?: "BLUE", area, at, eventId) + ("type" to JsonPrimitive("tanbon_score_command")) +
            ("competitor" to (competitor?.let(::JsonPrimitive) ?: JsonNull)))

    /** A `technical_score_send_command` of the seat from the current snapshot (ADR-011 §7), with the local total. */
    fun technical(
        technique: List<Int>,
        presentation: List<Int>,
        deduction: Int,
        at: Instant,
        eventId: String = UUID.randomUUID().toString(),
        total: Int = maxOf(0, technique.sum() + presentation.sum() - deduction),
    ): JsonObject {
        val current = requireNotNull(session) { "No session to judge" }
        val seat = current.getValue("seat").jsonObject
        return buildJsonObject {
            put("type", "technical_score_send_command")
            put("eventId", eventId)
            put("sequence", sequence++)
            put("clientTimestamp", at.toString())
            listOf("competitionId", "peerId", "courtId", "bracketId", "sessionId").forEach { put(it, current.string(it)) }
            put("judgeId", seat.string("judgeId"))
            put("deviceId", seat.string("deviceId"))
            put("discipline", current.string("discipline"))
            put("techniqueTenths", JsonArray(technique.map(::JsonPrimitive)))
            put("presentationTenths", JsonArray(presentation.map(::JsonPrimitive)))
            put("deductionTenths", deduction)
            put("totalTenths", total)
        }
    }

    /** Sends a frame without waiting for its reply, as a phone does just before it loses the connection. */
    suspend fun fire(command: JsonObject) = send(command.toString())

    /** Sends a command and returns its reply (`command_ack` or `command_rejected`), keeping pushes for later. */
    suspend fun command(command: JsonObject): JsonObject {
        send(command.toString())
        val eventId = command["eventId"]?.jsonPrimitive?.content
        return next { (it.string("type") == "command_ack" || it.string("type") == "command_rejected") && it.stringOrNull("eventId") == eventId }
    }

    suspend fun raw(text: String): JsonObject {
        send(text)
        return receive()
    }

    /** The next push of [type], applied to the local state like the client does. */
    suspend fun push(type: String): JsonObject = next { it.string("type") == type }.also(::apply)

    /** Frames received but not yet consumed. */
    fun pending(): List<JsonObject> = buffered.toList()

    suspend fun close() = socket.close()

    /** Drops the socket without a close handshake, as a phone does when the network is gone. */
    fun abort() {
        if (::socket.isInitialized) socket.cancel()
    }

    private fun apply(message: JsonObject) {
        val messageCursor = message.getValue("cursor").jsonPrimitive.long
        when (message.string("type")) {
            "resync_response", "session_snapshot" -> {
                session = message["session"] as? JsonObject
                cursor = messageCursor
            }
            "session_state_updated" -> if (cursor == null || messageCursor > cursor!!) {
                session = session?.let { JsonObject(it + ("state" to message.getValue("state")) + ("result" to message.getValue("result"))) }
                cursor = messageCursor
            }
            "kerugi_score_updated", "tanbon_score_updated", "technical_score_updated" -> if (cursor == null || messageCursor > cursor!!) {
                session = session?.let { JsonObject(it + ("score" to message.getValue("score"))) }
                cursor = messageCursor
            }
        }
    }

    private suspend fun next(matches: (JsonObject) -> Boolean): JsonObject {
        buffered.firstOrNull(matches)?.let { buffered.remove(it); return it }
        while (true) {
            val message = receive()
            if (matches(message)) return message
            buffered += message
        }
    }

    private suspend fun send(text: String) = socket.send(Frame.Text(text))

    private suspend fun receive(): JsonObject = withTimeout(replyTimeoutMillis) {
        Json.parseToJsonElement((socket.incoming.receive() as Frame.Text).readText()).jsonObject
    }
}

fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A `kerugi_score_command` of the seated device, built from a snapshot `session` body as the client builds it. */
fun scoreCommandFor(session: JsonObject, eventId: String, at: Instant, competitor: String = "BLUE", area: String = "HEAD") =
    buildJsonObject {
        val seat = session.getValue("seat").jsonObject
        put("type", "kerugi_score_command")
        put("eventId", eventId)
        put("sequence", 0)
        put("clientTimestamp", at.toString())
        listOf("competitionId", "peerId", "courtId", "bracketId", "sessionId").forEach { put(it, session.string(it)) }
        put("judgeId", seat.string("judgeId"))
        put("deviceId", seat.string("deviceId"))
        put("competitor", competitor)
        put("area", area)
    }

/** A Kerugi bout of a test competition judged by [judges] (paired device IDs). */
fun testBoutSpec(judges: List<String>, quorum: Int = 2) = BoutStartSpec(
    competitionId = CompetitionId("3f6c1d2e-8a4b-4c7d-9e1f-0a2b3c4d5e60"),
    bracketId = BracketId("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c40"),
    categoryId = "category",
    categoryTitle = "Весовая категория · Юноши · до 45 кг",
    boutNumber = 7,
    round = 1,
    index = 0,
    blue = BoutCorner("entry-1", "Иванов И. И. (Москва)", listOf(BoutAthlete("Иванов Иван Иванович", "Москва"))),
    red = BoutCorner("entry-2", "Петров П. П. (Казань)", listOf(BoutAthlete("Петров Пётр Петрович", "Казань"))),
    judges = judges.mapIndexed { index, device -> RosterJudge(device, "Судья${index + 1}") },
    quorum = quorum,
)
