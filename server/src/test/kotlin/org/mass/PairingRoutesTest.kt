package org.mass

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
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
        val pairingRequests = PairingRequests()
        application {
            module(pairingRequests = pairingRequests)
        }

        val first = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-3","surname":"Petrova","platform":"ios"}""")
        }
        val retry = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-3","surname":"Petrova","platform":"ios"}""")
        }
        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content
        val retryId = Json.parseToJsonElement(retry.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content

        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(firstId, retryId)
        assertEquals(1, pairingRequests.pending().size)
    }

    @Test
    fun `invalid pairing request is rejected without creating pending state`() = testApplication {
        val pairingRequests = PairingRequests()
        application {
            module(pairingRequests = pairingRequests)
        }

        val response = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"unknown-1","surname":" ","platform":"desktop"}""")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_pairing_request", body.getValue("code").jsonPrimitive.content)
        assertEquals(emptyList(), pairingRequests.pending())
    }

    @Test
    fun `pending surnames are not listed to anonymous LAN clients`() = testApplication {
        val pairingRequests = PairingRequests()
        application {
            module(pairingRequests = pairingRequests)
        }
        client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-private","surname":"Petrova","platform":"ios"}""")
        }

        val response = client.get("/v1/pairing-requests")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertTrue("Petrova" !in response.bodyAsText())
    }

    @Test
    fun `pairing revocation has no anonymous HTTP endpoint`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val response = client.post("/v1/pairing-requests/request-1/revoke")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `public pairing status exposes only pending state and opaque device identity`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        val submitted = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"android-8","surname":"Secret","platform":"android"}""")
        }
        val requestId = Json.parseToJsonElement(submitted.bodyAsText()).jsonObject
            .getValue("requestId").jsonPrimitive.content
        val response = client.get("/v1/pairing-status/$requestId")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("pairing_status", body.getValue("type").jsonPrimitive.content)
        assertEquals("pending", body.getValue("state").jsonPrimitive.content)
        assertEquals("android-8", body.getValue("deviceId").jsonPrimitive.content)
        assertEquals(null, body["code"])
        assertEquals(null, body["surname"])
        assertEquals(null, body["reconnectCredential"])
    }

    @Test
    fun `public pairing status reflects accepted and rejected decisions without sensitive fields`() = testApplication {
        val pairingRequests = PairingRequests()
        application {
            module(pairingRequests = pairingRequests)
        }

        val acceptedRequestId = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("ios-8", "Secret", "ios")),
        ).request.requestId
        pairingRequests.approve(acceptedRequestId)
        val rejectedRequestId = assertIs<PairingSubmission.Pending>(
            pairingRequests.submit(PairingRequestCommand("android-10", "Secret", "android")),
        ).request.requestId
        pairingRequests.reject(rejectedRequestId)

        val accepted = Json.parseToJsonElement(client.get("/v1/pairing-status/$acceptedRequestId").bodyAsText()).jsonObject
        val rejected = Json.parseToJsonElement(client.get("/v1/pairing-status/$rejectedRequestId").bodyAsText()).jsonObject

        assertEquals("accepted", accepted.getValue("state").jsonPrimitive.content)
        assertEquals(null, accepted["code"])
        assertEquals("rejected", rejected.getValue("state").jsonPrimitive.content)
        assertEquals("operator_rejected", rejected.getValue("code").jsonPrimitive.content)
        listOf(accepted, rejected).forEach { status ->
            assertEquals("pairing_status", status.getValue("type").jsonPrimitive.content)
            assertEquals(null, status["surname"])
            assertEquals(null, status["reconnectCredential"])
        }
    }

    @Test
    fun `secure matching delivery proof exposes only its accepted credential`() = testApplication {
        val pairingRequests = PairingRequests()
        application { module(pairingRequests = pairingRequests, credentialDeliveryIsSecure = { true }) }
        val submitted = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-proof","surname":"Petrova","platform":"ios","deliveryProof":"client-proof-1"}""")
        }
        val requestId = Json.parseToJsonElement(submitted.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content
        val accepted = assertIs<PairingApproval.Accepted>(pairingRequests.approve(requestId))

        val publicStatus = Json.parseToJsonElement(client.get("/v1/pairing-status/$requestId").bodyAsText()).jsonObject
        val delivered = Json.parseToJsonElement(client.get("/v1/pairing-status/$requestId") {
            header(PAIRING_DELIVERY_PROOF_HEADER, "client-proof-1")
        }.bodyAsText()).jsonObject

        assertEquals(null, publicStatus["reconnectCredential"])
        assertEquals(accepted.request.reconnectCredential, delivered.getValue("reconnectCredential").jsonPrimitive.content)
    }

    @Test
    fun `missing invalid and insecure delivery proofs never disclose a credential`() = testApplication {
        val pairingRequests = PairingRequests()
        application { module(pairingRequests = pairingRequests, credentialDeliveryIsSecure = { false }) }
        val submitted = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"android-proof","surname":"Ivanov","platform":"android","deliveryProof":"client-proof-2"}""")
        }
        val requestId = Json.parseToJsonElement(submitted.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content
        pairingRequests.approve(requestId)

        listOf(null, "wrong-proof", "client-proof-2").forEach { proof ->
            val response = client.get("/v1/pairing-status/$requestId") { proof?.let { header(PAIRING_DELIVERY_PROOF_HEADER, it) } }
            assertEquals(null, Json.parseToJsonElement(response.bodyAsText()).jsonObject["reconnectCredential"])
        }
    }

    @Test
    fun `a new delivery proof replaces the pending request of the same device`() = testApplication {
        val pairingRequests = PairingRequests()
        application { module(pairingRequests = pairingRequests, credentialDeliveryIsSecure = { true }) }
        val first = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-proof-retry","surname":"Petrova","platform":"ios","deliveryProof":"client-proof-3"}""")
        }
        val retry = client.post("/v1/pairing-requests") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"deviceId":"ios-proof-retry","surname":"Petrova","platform":"ios","deliveryProof":"other-proof"}""")
        }
        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content
        val retryId = Json.parseToJsonElement(retry.bodyAsText()).jsonObject.getValue("requestId").jsonPrimitive.content

        assertEquals(HttpStatusCode.Accepted, retry.status)
        assertEquals(listOf(retryId), pairingRequests.pending().map(PendingPairingRequest::requestId))
        val replaced = Json.parseToJsonElement(client.get("/v1/pairing-status/$firstId").bodyAsText()).jsonObject
        assertEquals("rejected" to "superseded", replaced.getValue("state").jsonPrimitive.content to replaced.getValue("code").jsonPrimitive.content)

        pairingRequests.approve(retryId)
        val credential = Json.parseToJsonElement(
            client.get("/v1/pairing-status/$retryId") { header(PAIRING_DELIVERY_PROOF_HEADER, "other-proof") }.bodyAsText(),
        ).jsonObject["reconnectCredential"]?.jsonPrimitive?.content
        assertTrue(!credential.isNullOrBlank())
    }

    @Test
    fun `unknown pairing status returns not found`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/pairing-status/unknown-request").status)
    }

    @Test
    fun `pairing decisions have no anonymous post or put endpoints`() = testApplication {
        application {
            module(pairingRequests = PairingRequests())
        }

        listOf("approve", "reject", "revoke").forEach { decision ->
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/pairing-requests/request-1/$decision").status)
            assertEquals(HttpStatusCode.NotFound, client.put("/v1/pairing-requests/request-1/$decision").status)
        }
    }
}
