package org.mass

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Acceptance tests of the production composition against the bundled PostgreSQL and real sockets. */
class ServerRuntimeTest {
    private val root = createTempDirectory()
    private val http = HttpClient.newHttpClient()
    private val runtimes = mutableListOf<ServerRuntime>()

    @AfterTest
    fun cleanUp() {
        runtimes.forEach(ServerRuntime::stop)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `keeps the peer identity and acknowledged commands across a restart`() {
        val port = freePort()
        val firstRun = runtime(port)
        val running = assertIs<ServerRuntimeState.Running>(firstRun.start())
        assertEquals(running.peerId.value, metadata(port)["peerId"])

        val credential = pairAndApprove(firstRun, port)
        val command = command(eventId = "event-1")
        realtime(port, credential).use { socket ->
            assertEquals("command_ack", socket.request(command)["type"])
        }
        firstRun.stop()

        val secondRun = runtime(port)
        assertEquals(running.peerId, assertIs<ServerRuntimeState.Running>(secondRun.start()).peerId)
        val reconnectCredential = pairAndApprove(secondRun, port)
        realtime(port, reconnectCredential).use { socket ->
            assertEquals("command_ack", socket.request(command)["type"])
            val resync = socket.request("""{"type":"resync_request","cursor":null}""")
            assertEquals("resync_response", resync["type"])
            val events = Json.parseToJsonElement(socket.lastText).jsonObject.getValue("events").jsonArray
            assertEquals(listOf("event-1"), events.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        }
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
    fun `fails startup and releases PostgreSQL when the HTTP port is taken`() {
        ServerSocket(0, 1, InetAddress.getByName("0.0.0.0")).use { occupied ->
            val runtime = runtime(occupied.localPort)
            val failed = assertIs<ServerRuntimeState.Failed>(runtime.start())
            assertTrue(failed.diagnostic.contains("HTTP port ${occupied.localPort}"))
            assertEquals(false, Files.exists(root.resolve("application-data/postgres/postmaster.pid")))
        }
    }

    @Test
    fun `fails startup without a PostgreSQL bundle`() {
        val runtime = ServerRuntime(ServerRuntimeConfiguration(root.resolve("missing"), root.resolve("application-data"), freePort()))
        assertIs<ServerRuntimeState.Failed>(runtime.start())
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
        val response = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/pairing-requests"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"deviceId":"device-1","surname":"Ivanov","platform":"android"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val requestId = Json.parseToJsonElement(response.body()).jsonObject.getValue("requestId").jsonPrimitive.content
        return assertIs<PairingApproval.Accepted>(runtime.pairingRequests.approve(requestId)).request.reconnectCredential
    }

    private fun command(eventId: String) =
        """{"type":"command","eventId":"$eventId","sequence":1,"clientTimestamp":"2026-09-26T10:00:00Z",""" +
            """"sessionId":"session-1","payload":{"type":"kerugi_score"}}"""

    private fun get(port: Int, path: String): String = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun realtime(port: Int, credential: String): RealtimeSocket {
        val socket = RealtimeSocket()
        socket.webSocket = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$port/v1/realtime"), socket).get(5, TimeUnit.SECONDS)
        assertEquals(
            "handshake_accepted",
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
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }
}
