package org.mass

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mass.bout.BoutAthlete
import org.mass.bout.BoutCommand
import org.mass.bout.BoutCorner
import org.mass.bout.BoutEventJournal
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStartSpec
import org.mass.bout.BoutStateAction
import org.mass.bout.KerugiBoutService
import org.mass.bout.RosterJudge
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.PeerId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wire contract shared with u-judge-client (`CLI-103`). The fixtures in `src/test/resources/contract/v1` are the canonical
 * message shapes: the client generates its contract tests from them, so a change here must be synchronized there. Values
 * that the server generates (IDs, credentials, timestamps) are compared by presence, everything else literally.
 */
class ContractFixturesTest {
    private val metadata = ServerMetadata(
        protocolVersion = "1.0",
        capabilities = mapOf("metadata" to true, "kerugi_bout" to true),
        peerId = "00000000-0000-4000-8000-000000000001",
        courtId = "court-local",
        serverName = "U'Judge Server",
        pairingPolicy = "operator-approval",
        serverTime = "2026-09-26T09:00:00Z",
    )

    @Test
    fun `HTTP metadata and pairing messages match the fixtures`() = testApplication {
        val pairing = PairingRequests()
        contractModule(pairing)

        assertMatches("metadata_response", client.get("/v1/metadata").bodyAsText())

        val pending = client.post("/v1/pairing-requests") {
            contentType(ContentType.Application.Json)
            setBody(fixture("pairing_request").toString())
        }.bodyAsText()
        assertMatches("pairing_pending_response", pending, generated = setOf("requestId"))
        val requestId = Json.parseToJsonElement(pending).jsonObject.getValue("requestId").jsonPrimitive.content

        assertMatches("pairing_status_pending", status(requestId))
        pairing.approve(requestId)
        assertMatches("pairing_status_accepted", status(requestId), generated = setOf("reconnectCredential"))

        val rejected = Json.parseToJsonElement(
            client.post("/v1/pairing-requests") {
                contentType(ContentType.Application.Json)
                setBody(fixture("pairing_request").toString())
            }.bodyAsText(),
        ).jsonObject.getValue("requestId").jsonPrimitive.content
        pairing.reject(rejected)
        assertMatches("pairing_status_rejected", status(rejected))
    }

    @Test
    fun `realtime messages match the fixtures`() = testApplication {
        val pairing = PairingRequests()
        contractModule(pairing)
        val submitted = pairing.submit(PairingRequestCommand("android-contract", "Ivanov", "android")) as PairingSubmission.Pending
        val credential = (pairing.approve(submitted.request.requestId) as PairingApproval.Accepted).request.reconnectCredential
        val socket = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")

        assertMatches("handshake_accepted", socket.exchange(withCredential(fixture("handshake_request"), credential)))
        assertMatches(
            "clock_sync_response",
            socket.exchange(fixture("clock_sync_request")),
            generated = setOf("serverReceiveTimestamp", "serverSendTimestamp"),
        )
        assertMatches("heartbeat_ack", socket.exchange(fixture("heartbeat_request")))

        pairing.revoke(submitted.request.requestId)

        val revokedSocket = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        assertMatches("handshake_rejected", revokedSocket.exchange(withCredential(fixture("handshake_request"), credential)))
    }

    @Test
    fun `Kerugi bout messages match the fixtures`() = testApplication {
        val pairing = PairingRequests()
        var clock = Instant.parse("2026-11-08T10:15:30Z")
        val hub = RealtimeHub(KerugiBoutService(BoutEventJournal.InMemory(), PeerId(metadata.peerId), now = { clock }))
        contractModule(pairing, hub)
        val judge = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        val second = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        judge.exchange(withCredential(fixture("handshake_request"), approve(pairing, "android-contract")))
        second.exchange(withCredential(fixture("handshake_request"), approve(pairing, "android-2")))
        assertMatches("resync_response_idle", judge.exchange(fixture("resync_request")))
        second.exchange(fixture("resync_request"))

        val sessionId = (hub.operator("Оператор", BoutCommand.StartBout(EventId.new(), boutSpec())) as BoutOutcome.Applied).sessionId
        val bout = hub.bouts.current()!!
        val generated = mapOf(
            "sessionId" to sessionId.value,
            "courtId" to bout.courtId.value,
            "judgeId" to bout.seatOf("android-contract")!!.judgeId,
        )
        val sessionPaths = setOf("cursor", "session.sessionId", "session.courtId", "session.seat.judgeId")
        assertMatches("session_snapshot", judge.receiveText(), generated = sessionPaths)
        second.receiveText()
        hub.operator("Оператор", BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.START))
        judge.receiveText()
        second.receiveText()

        val score = withValues(fixture("kerugi_score_command"), generated)
        assertMatches("command_ack", judge.exchange(score))
        val secondTap = withValues(
            score,
            mapOf("eventId" to EventId.new().value, "judgeId" to bout.seatOf("android-2")!!.judgeId, "deviceId" to "android-2",
                "clientTimestamp" to "2026-11-08T10:15:30.500Z"),
        )
        assertEquals("command_ack", Json.parseToJsonElement(second.exchange(secondTap)).jsonObject.getValue("type").jsonPrimitive.content)
        assertMatches("command_rejected", judge.exchangeSkipping(withValues(score, mapOf("area" to "BODY")), "kerugi_score_updated"))
        clock = Instant.parse("2026-11-08T10:15:41Z")
        val attention = withValues(fixture("judge_attention_command"), generated)
        assertEquals(attention.getValue("eventId"), Json.parseToJsonElement(judge.exchange(attention)).jsonObject.getValue("eventId"))

        repeat(10) { hub.operator("Оператор", BoutCommand.AwardAction(EventId.new(), sessionId, KerugiOperatorActionType.GAMJEOM, KerugiCompetitor.RED, 1)) }
        repeat(9) { judge.receiveText() }
        assertMatches("kerugi_score_updated", judge.receiveText(), generated = setOf("cursor", "sessionId"))
        assertMatches("resync_response", judge.exchange(fixture("resync_request")), generated = sessionPaths)
        val invalidResync = JsonObject(fixture("resync_request") + ("cursor" to JsonPrimitive("latest")))
        assertMatches("resync_rejected", judge.exchange(invalidResync))

        hub.operator("Оператор", BoutCommand.CompleteBout(EventId.new(), sessionId))
        assertMatches("session_state_updated", judge.receiveText(), generated = setOf("cursor", "sessionId"))
    }

    private fun approve(pairing: PairingRequests, deviceId: String): String {
        val submitted = pairing.submit(PairingRequestCommand(deviceId, "Ivanov", "android")) as PairingSubmission.Pending
        return (pairing.approve(submitted.request.requestId) as PairingApproval.Accepted).request.reconnectCredential
    }

    private fun boutSpec(): BoutStartSpec {
        val snapshot = fixture("session_snapshot").getValue("session").jsonObject
        fun corner(side: String) = snapshot.getValue(side).jsonObject.let { corner ->
            BoutCorner(
                corner.getValue("entryId").jsonPrimitive.content,
                corner.getValue("label").jsonPrimitive.content,
                corner.getValue("athletes").jsonArray.map {
                    BoutAthlete(it.jsonObject.getValue("fullName").jsonPrimitive.content, it.jsonObject.getValue("city").jsonPrimitive.content)
                },
            )
        }
        return BoutStartSpec(
            competitionId = CompetitionId(snapshot.getValue("competitionId").jsonPrimitive.content),
            bracketId = BracketId(snapshot.getValue("bracketId").jsonPrimitive.content),
            categoryId = "category",
            categoryTitle = snapshot.getValue("categoryTitle").jsonPrimitive.content,
            boutNumber = snapshot.getValue("boutNumber").jsonPrimitive.content.toInt(),
            round = 1,
            index = 0,
            blue = corner("blue"),
            red = corner("red"),
            judges = listOf(RosterJudge("android-contract", "Ivanov"), RosterJudge("android-2", "Petrova")),
        )
    }

    private fun withValues(message: JsonObject, values: Map<String, String>) =
        JsonObject(message.mapValues { (key, value) -> values[key]?.let(::JsonPrimitive) ?: value })

    private suspend fun WebSocketSession.receiveText() = (incoming.receive() as Frame.Text).readText()

    /** Sends [message] and returns the first reply that is not a push of [pushType]. */
    private suspend fun WebSocketSession.exchangeSkipping(message: JsonObject, pushType: String): String {
        send(Frame.Text(message.toString()))
        while (true) {
            val text = receiveText()
            if (Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content != pushType) return text
        }
    }

    private fun ApplicationTestBuilder.contractModule(pairing: PairingRequests, realtime: RealtimeHub = RealtimeHub.inMemory()) = application {
        module(
            metadata = metadata,
            pairingRequests = pairing,
            realtime = realtime,
            credentialDeliveryIsSecure = { true },
            clock = { Instant.parse("2026-09-26T10:00:00Z") },
        )
    }

    private suspend fun ApplicationTestBuilder.status(requestId: String): String {
        val request = fixture("pairing_status_request")
        return client.get(request.getValue("path").jsonPrimitive.content.replace("{requestId}", requestId)) {
            header(request.getValue("deliveryProofHeader").jsonPrimitive.content, fixture("pairing_request").getValue("deliveryProof").jsonPrimitive.content)
        }.bodyAsText()
    }

    private suspend fun WebSocketSession.exchange(message: JsonObject): String {
        send(Frame.Text(message.toString()))
        return (incoming.receive() as Frame.Text).readText()
    }

    private fun withCredential(message: JsonObject, credential: String) =
        JsonObject(message + ("reconnectCredential" to JsonPrimitive(credential)))

    /** [generated] lists dotted paths of server-generated values, compared by presence; everything else literally. */
    private fun assertMatches(name: String, actualText: String, generated: Set<String> = emptySet()) {
        val expected = fixture(name)
        val actual = Json.parseToJsonElement(actualText).jsonObject
        assertEquals(expected.keys, actual.keys, "$name keys")
        val normalized = generated.fold(actual) { message, path -> replaceGenerated(name, message, expected, path.split(".")) }
        assertEquals<JsonElement>(expected, normalized, name)
    }

    private fun replaceGenerated(name: String, actual: JsonObject, expected: JsonObject, path: List<String>): JsonObject {
        val key = path.first()
        val value = actual.getValue(key)
        return if (path.size == 1) {
            assert(value is JsonPrimitive) { "$name.$key must be a value" }
            JsonObject(actual + (key to expected.getValue(key)))
        } else {
            JsonObject(actual + (key to replaceGenerated(name, value.jsonObject, expected.getValue(key).jsonObject, path.drop(1))))
        }
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/contract/v1/$name.json")) { "missing fixture $name" }.readText(),
    ).jsonObject
}
