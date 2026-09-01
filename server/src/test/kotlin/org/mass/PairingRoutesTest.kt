package org.mass

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PairingRoutesTest {
    @Test
    fun `pairing request creates a pending Android judge request`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val response = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"android-7","surname":"Ivanov","platform":"android"}""")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("pending", body.getValue("state").jsonPrimitive.content)
        assertEquals("android-7", body.getValue("deviceId").jsonPrimitive.content)
        assertEquals("Ivanov", body.getValue("surname").jsonPrimitive.content)
        assertEquals("android", body.getValue("platform").jsonPrimitive.content)
    }

    @Test
    fun `repeated pairing request for a device keeps one pending request`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val first = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-3","surname":"Petrova","platform":"ios"}""")
        }
        val retry = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-3","surname":"Petrova","platform":"ios"}""")
        }
        val requests = client.get("/v1/pairing-requests")
        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content
        val retryId = Json.parseToJsonElement(retry.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content
        val pendingRequests = Json.parseToJsonElement(requests.bodyAsText()).jsonArray

        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(firstId, retryId)
        assertEquals(1, pendingRequests.size)
    }

    @Test
    fun `invalid pairing request is rejected without creating pending state`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val response = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"unknown-1","surname":" ","platform":"desktop"}""")
        }
        val requests = client.get("/v1/pairing-requests")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_pairing_request", body.getValue("code").jsonPrimitive.content)
        assertEquals(0, Json.parseToJsonElement(requests.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `pairing revocation has no anonymous HTTP endpoint`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val response = client.post("/v1/pairing-requests/request-1/revoke")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
