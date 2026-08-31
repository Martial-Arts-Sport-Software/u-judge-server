package org.mass

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MetadataRoutesTest {
    @Test
    fun `metadata publishes compatibility and court identity before pairing`() = testApplication {
        val configuredMetadata = ServerMetadata(
            protocolVersion = "1.0",
            capabilities = mapOf("metadata" to true),
            peerId = "peer-47",
            courtId = "court-2",
            serverName = "Court Two",
            pairingPolicy = "operator-approval",
            serverTime = "2026-08-31T12:00:00Z",
        )
        application {
            module(configuredMetadata)
        }

        val response = client.get("/v1/metadata")
        val metadata = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1.0", metadata.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("peer-47", metadata.getValue("peerId").jsonPrimitive.content)
        assertEquals("court-2", metadata.getValue("courtId").jsonPrimitive.content)
        assertEquals("Court Two", metadata.getValue("serverName").jsonPrimitive.content)
        assertEquals("operator-approval", metadata.getValue("pairingPolicy").jsonPrimitive.content)
        assertTrue(metadata.getValue("capabilities").jsonObject.isNotEmpty())
        assertEquals("2026-08-31T12:00:00Z", metadata.getValue("serverTime").jsonPrimitive.content)
    }

    @Test
    fun `legacy anonymous score endpoint is unavailable`() = testApplication {
        application {
            module()
        }

        val response = client.post("/score")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
