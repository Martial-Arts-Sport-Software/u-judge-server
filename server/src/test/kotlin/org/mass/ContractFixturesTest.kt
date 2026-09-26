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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        capabilities = mapOf("metadata" to true),
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
        assertMatches("command_ack", socket.exchange(fixture("command_request")))

        val conflicting = JsonObject(fixture("command_request") + ("sequence" to JsonPrimitive(2)))
        assertMatches("command_rejected", socket.exchange(conflicting))

        pairing.revoke(submitted.request.requestId)

        val revokedSocket = createClient { install(WebSockets) }.webSocketSession("/v1/realtime")
        assertMatches("handshake_rejected", revokedSocket.exchange(withCredential(fixture("handshake_request"), credential)))
    }

    private fun ApplicationTestBuilder.contractModule(pairing: PairingRequests) = application {
        module(
            metadata = metadata,
            pairingRequests = pairing,
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

    private fun assertMatches(name: String, actualText: String, generated: Set<String> = emptySet()) {
        val expected = fixture(name)
        val actual = Json.parseToJsonElement(actualText).jsonObject
        assertEquals(expected.keys, actual.keys, "$name keys")
        val normalized = JsonObject(actual.mapValues { (key, value) -> if (key in generated) expected.getValue(key) else value })
        generated.forEach { key -> assert(actual.getValue(key) is JsonPrimitive) { "$name.$key must be a value" } }
        assertEquals<JsonElement>(expected, normalized, name)
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/contract/v1/$name.json")) { "missing fixture $name" }.readText(),
    ).jsonObject
}
