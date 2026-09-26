package org.mass

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Acceptance tests of the production composition against the bundled PostgreSQL and real sockets. */
class ServerRuntimeTest {
    private val root = createTempDirectory()
    /** The SPKI hash captured on first contact, like a phone pins it (ADR-006). */
    private var pinnedSpki: ByteArray = ByteArray(0)
    private val http: HttpClient = HttpClient.newBuilder().sslContext(pinningContext()).build()
    private val runtimes = mutableListOf<ServerRuntime>()

    @AfterTest
    fun cleanUp() {
        runtimes.forEach(ServerRuntime::stop)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `keeps the peer identity, certificate, device approval and acknowledged commands across a restart`() {
        val port = freePort()
        val firstRun = runtime(port)
        val running = assertIs<ServerRuntimeState.Running>(firstRun.start())
        assertEquals(running.peerId.value, metadata(port)["peerId"])
        assertEquals(running.verificationCode, PeerCertificate.verificationCode(pinnedSpki))

        val credential = pairAndApprove(firstRun, port)
        val command = command(eventId = "event-1")
        realtime(port, credential).use { socket ->
            assertEquals("command_ack", socket.request(command)["type"])
        }
        firstRun.stop()

        val secondRun = runtime(port)
        val restarted = assertIs<ServerRuntimeState.Running>(secondRun.start())
        assertEquals(running.peerId, restarted.peerId)
        assertEquals(running.verificationCode, restarted.verificationCode)
        assertEquals(listOf("device-1"), secondRun.pairingRequests.operatorRegistry().devices.map(OperatorDevice::deviceId))
        realtime(port, credential).use { socket ->
            assertEquals("command_ack", socket.request(command)["type"])
            val resync = socket.request("""{"type":"resync_request","cursor":null}""")
            assertEquals("resync_response", resync["type"])
            val events = Json.parseToJsonElement(socket.lastText).jsonObject.getValue("events").jsonArray
            assertEquals(listOf("event-1"), events.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        }
    }

    @Test
    fun `serves only TLS and delivers the credential to the matching delivery proof`() {
        val port = freePort()
        val runtime = runtime(port)
        assertIs<ServerRuntimeState.Running>(runtime.start())

        assertFailsWith<java.io.IOException> {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/metadata")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        val requestId = submitPairing(port, deliveryProof = "proof-tls")
        runtime.pairingRequests.approve(requestId)

        assertEquals(null, pairingStatus(port, requestId, deliveryProof = "other-proof")["reconnectCredential"])
        val credential = requireNotNull(pairingStatus(port, requestId, deliveryProof = "proof-tls")["reconnectCredential"])
        realtime(port, credential).use { socket ->
            assertEquals("command_ack", socket.request(command("event-tls"))["type"])
            assertEquals(DeviceConnectionState.CONNECTED, runtime.pairingRequests.operatorRegistry().devices.single().connectionState)

            runtime.pairingRequests.revoke(requestId)

            val rejected = socket.request(command("event-after-revoke"))
            assertEquals("command_rejected" to "invalid_reconnect_credential", rejected["type"] to rejected["code"])
        }
        realtime(port, credential, expectAccepted = false).close()
        assertEquals(true, runtime.pairingRequests.operatorRegistry().devices.single().revoked)
    }

    @Test
    fun `reports a PostgreSQL failure while running`() {
        val runtime = runtime(freePort())
        assertIs<ServerRuntimeState.Running>(runtime.start())

        val pid = Files.readAllLines(root.resolve("application-data/postgres/postmaster.pid")).first().trim().toLong()
        ProcessHandle.of(pid).orElseThrow().destroyForcibly()

        val failed = awaitState(runtime) { it is ServerRuntimeState.Failed }
        assertTrue((failed as ServerRuntimeState.Failed).diagnostic.contains("PostgreSQL"))
        assertIs<ServerRuntimeState.Running>(runtime.restart())
    }

    @Test
    fun `fails startup and releases PostgreSQL when the HTTPS port is taken`() {
        ServerSocket(0, 1, InetAddress.getByName("0.0.0.0")).use { occupied ->
            val runtime = runtime(occupied.localPort)
            val failed = assertIs<ServerRuntimeState.Failed>(runtime.start())
            assertTrue(failed.diagnostic.contains("HTTPS port ${occupied.localPort}"))
            assertEquals(false, Files.exists(root.resolve("application-data/postgres/postmaster.pid")))
        }
    }

    @Test
    fun `fails startup without a PostgreSQL bundle`() {
        val runtime = ServerRuntime(ServerRuntimeConfiguration(root.resolve("missing"), root.resolve("application-data"), freePort()))
        assertIs<ServerRuntimeState.Failed>(runtime.start())
    }

    @Test
    fun `restores executable permissions that desktop packaging dropped`() {
        val bin = Files.createDirectories(root.resolve("bundle/postgresql/bin"))
        val postgres = Files.writeString(bin.resolve("postgres"), "#!/bin/sh\n")
        postgres.toFile().setExecutable(false, false)

        val runtime = ServerRuntime(ServerRuntimeConfiguration(root.resolve("bundle"), root.resolve("application-data"), freePort()))

        assertEquals(null, runtime.restoreExecutablePermissions(bin))
        assertTrue(Files.isExecutable(postgres))
    }

    private fun runtime(port: Int): ServerRuntime {
        val installationDirectory = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(!installationDirectory.isNullOrBlank(), "Requires the PostgreSQL bundle prepared by Gradle")
        return ServerRuntime(
            ServerRuntimeConfiguration(Path.of(installationDirectory), root.resolve("application-data"), port, "JudgeServer-test"),
            supervisionInterval = java.time.Duration.ofMillis(100),
        ).also(runtimes::add)
    }

    private fun metadata(port: Int): Map<String, String> =
        Json.parseToJsonElement(get(port, "/v1/metadata")).jsonObject
            .filterValues { runCatching { it.jsonPrimitive }.isSuccess }
            .mapValues { it.value.jsonPrimitive.content }

    private fun pairAndApprove(runtime: ServerRuntime, port: Int): String {
        val requestId = submitPairing(port, deliveryProof = "proof-1")
        runtime.pairingRequests.approve(requestId)
        return requireNotNull(pairingStatus(port, requestId, deliveryProof = "proof-1")["reconnectCredential"])
    }

    private fun submitPairing(port: Int, deliveryProof: String): String {
        val response = http.send(
            HttpRequest.newBuilder(URI("https://127.0.0.1:$port/v1/pairing-requests"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"deviceId":"device-1","surname":"Ivanov","platform":"android","deliveryProof":"$deliveryProof"}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Json.parseToJsonElement(response.body()).jsonObject.getValue("requestId").jsonPrimitive.content
    }

    private fun pairingStatus(port: Int, requestId: String, deliveryProof: String): Map<String, String> =
        Json.parseToJsonElement(
            http.send(
                HttpRequest.newBuilder(URI("https://127.0.0.1:$port/v1/pairing-status/$requestId"))
                    .header(PAIRING_DELIVERY_PROOF_HEADER, deliveryProof)
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        ).jsonObject.filterValues { runCatching { it.jsonPrimitive }.isSuccess }.mapValues { it.value.jsonPrimitive.content }

    private fun command(eventId: String) =
        """{"type":"command","eventId":"$eventId","sequence":1,"clientTimestamp":"2026-09-26T10:00:00Z",""" +
            """"sessionId":"session-1","payload":{"type":"kerugi_score"}}"""

    private fun get(port: Int, path: String): String = http.send(
        HttpRequest.newBuilder(URI("https://127.0.0.1:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun realtime(port: Int, credential: String, expectAccepted: Boolean = true): RealtimeSocket {
        val socket = RealtimeSocket()
        socket.webSocket = http.newWebSocketBuilder().buildAsync(URI("wss://127.0.0.1:$port/v1/realtime"), socket).get(5, TimeUnit.SECONDS)
        assertEquals(
            if (expectAccepted) "handshake_accepted" else "handshake_rejected",
            socket.request("""{"type":"handshake","protocolVersion":"1.0","reconnectCredential":"$credential"}""")["type"],
        )
        return socket
    }

    private fun awaitState(runtime: ServerRuntime, predicate: (ServerRuntimeState) -> Boolean): ServerRuntimeState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            runtime.state.value.takeIf(predicate)?.let { return it }
            Thread.sleep(50)
        }
        error("Runtime stayed ${runtime.state.value}")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Trusts the first presented key and then only that key, without hostname or CA checks. */
    private fun pinningContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        val trustManager = object : X509ExtendedTrustManager() {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = pin(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = pin(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = pin(chain)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = throw CertificateException()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = throw CertificateException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            private fun pin(chain: Array<X509Certificate>) {
                val spki = MessageDigest.getInstance("SHA-256").digest(chain.first().publicKey.encoded)
                if (pinnedSpki.isEmpty()) pinnedSpki = spki
                if (!pinnedSpki.contentEquals(spki)) throw CertificateException("server_identity_changed")
            }
        }
        init(null, arrayOf(trustManager), null)
    }

    private class RealtimeSocket : WebSocket.Listener, AutoCloseable {
        lateinit var webSocket: WebSocket
        private val messages = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()
        var lastText: String = ""

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) {
                messages.add(partial.toString())
                partial.clear()
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        fun request(text: String): Map<String, String> {
            webSocket.sendText(text, true).get(5, TimeUnit.SECONDS)
            lastText = requireNotNull(messages.poll(5, TimeUnit.SECONDS)) { "No reply to $text" }
            return Json.parseToJsonElement(lastText).jsonObject
                .filterValues { runCatching { it.jsonPrimitive }.isSuccess }
                .mapValues { it.value.jsonPrimitive.content }
        }

        override fun close() {
            runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
        }
    }
}
