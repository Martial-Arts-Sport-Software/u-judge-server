package org.mass

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HealthRoutesTest {
    @Test
    fun `health endpoint publishes a credential-free healthy status`() = testApplication {
        application {
            module()
        }

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val health = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("status"), health.keys)
        assertEquals("healthy", health.getValue("status").jsonPrimitive.content)
        assertFalse("surname" in health)
        assertFalse("reconnectCredential" in health)
    }
}
