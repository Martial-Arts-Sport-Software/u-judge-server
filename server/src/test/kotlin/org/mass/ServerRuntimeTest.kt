package org.mass

import kotlinx.serialization.json.Json
import org.mass.bout.BoutCommand
import org.mass.bout.BoutOutcome
import org.mass.bout.BoutStateAction
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.SessionState
import org.mass.persistence.BackupInspection
import org.mass.persistence.JournalBackup
import org.mass.persistence.JournalBackups
import org.mass.report.CompetitionReportTest
import org.mass.report.CompetitionScenario
import java.time.Instant
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
        val hub = requireNotNull(firstRun.realtime)
        val started = hub.operator("Оператор", BoutCommand.StartBout(EventId.new(), testBoutSpec(listOf("device-1", "device-2"), quorum = 1)))
        hub.operator("Оператор", BoutCommand.ChangeState(EventId.new(), (started as BoutOutcome.Applied).sessionId, BoutStateAction.START))
        lateinit var command: String
        realtime(port, credential).use { socket ->
            assertEquals("resync_response", socket.request(RESYNC_REQUEST)["type"])
            val session = Json.parseToJsonElement(socket.lastText).jsonObject.getValue("session").jsonObject
            command = scoreCommandFor(session, EventId.new().value, Instant.now()).toString()
            assertEquals("command_ack", socket.request(command)["type"])
        }
        firstRun.stop()

        val secondRun = runtime(port)
        val restarted = assertIs<ServerRuntimeState.Running>(secondRun.start())
        assertEquals(running.peerId, restarted.peerId)
        assertEquals(running.verificationCode, restarted.verificationCode)
        assertEquals(listOf("device-1"), secondRun.pairingRequests.operatorRegistry().devices.map(OperatorDevice::deviceId))
        realtime(port, credential).use { socket ->
            assertEquals("resync_response", socket.request(RESYNC_REQUEST)["type"])
            val session = Json.parseToJsonElement(socket.lastText).jsonObject.getValue("session").jsonObject
            // ADR-010 §3: after the restart the clock stands still until the operator resumes it.
            assertEquals("paused", session.string("state"))
            assertEquals("1", session.getValue("seat").jsonObject.getValue("number").jsonPrimitive.content)
            assertEquals("2", session.getValue("score").jsonObject.getValue("blue").jsonPrimitive.content)
            assertEquals("command_ack", socket.request(command)["type"])
        }
        assertEquals(2, secondRun.realtime!!.bouts.current()!!.score().blue)
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
            assertEquals("resync_response", socket.request(RESYNC_REQUEST)["type"])
            assertEquals(DeviceConnectionState.CONNECTED, runtime.pairingRequests.operatorRegistry().devices.single().connectionState)

            runtime.pairingRequests.revoke(requestId)

            assertEquals("credential_revoked", socket.closeReason.get(5, TimeUnit.SECONDS))
        }
        realtime(port, credential, expectAccepted = false).close()
        assertEquals(true, runtime.pairingRequests.operatorRegistry().devices.single().revoked)
    }

    @Test
    fun `backs up after a completed bout and restores the backup on a clean peer into a new database`() {
        val source = runtime(freePort())
        val running = assertIs<ServerRuntimeState.Running>(source.start())
        val hub = requireNotNull(source.realtime)
        val sessionId = (hub.operator("Оператор", BoutCommand.StartBout(EventId.new(), testBoutSpec(listOf("device-1", "device-2")))) as BoutOutcome.Applied).sessionId
        listOf(
            BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.START),
            BoutCommand.AwardAction(EventId.new(), sessionId, KerugiOperatorActionType.THROW, KerugiCompetitor.RED, 2),
            BoutCommand.EndPeriod(EventId.new(), sessionId),
            BoutCommand.ChangeState(EventId.new(), sessionId, BoutStateAction.RESUME),
            BoutCommand.EndPeriod(EventId.new(), sessionId),
            BoutCommand.CompleteBout(EventId.new(), sessionId),
        ).forEach { assertIs<BoutOutcome.Applied>(hub.operator("Оператор", it), it.toString()) }
        val result = hub.bouts.bout(sessionId)!!.result

        // `BAK-002`: the completed bout is in a separate copy without any operator action.
        val backups = requireNotNull(source.backups)
        val automatic = awaitValue { backups.list().firstOrNull { it.header?.reason == JournalBackups.AFTER_SESSION } }
        assertEquals(running.peerId.value, automatic.header!!.peerId)
        assertEquals(automatic.path, backups.status.value.lastPath)
        val copy = root.resolve("usb/competition.jsonl").also { backups.copy(automatic.path, it) }
        source.stop()

        // A clean computer: its own cluster and peer until the restore.
        val clean = runtime(freePort(), data = "clean-computer")
        val fresh = assertIs<ServerRuntimeState.Running>(clean.start())
        assertTrue(fresh.peerId != running.peerId)
        assertEquals(RestoreOutcome.Rejected(JournalBackup.UNREADABLE), clean.restore(root.resolve("usb/missing.jsonl")))
        Files.writeString(root.resolve("usb/broken.jsonl"), Files.readString(copy).replace("THROW", "SPIN_BONUS"))
        assertEquals(RestoreOutcome.Rejected(JournalBackup.CHECKSUM_MISMATCH), clean.restore(root.resolve("usb/broken.jsonl")))
        assertEquals(fresh.peerId, assertIs<ServerRuntimeState.Running>(clean.state.value).peerId)

        val restored = assertIs<RestoreOutcome.Restored>(clean.restore(copy))
        assertEquals(automatic.header!!.events, restored.events)
        assertEquals(automatic.header!!.sha256, restored.sha256)
        assertEquals(JournalBackups.BEFORE_RESTORE, JournalBackup.inspect(restored.safetyBackup).let { assertIs<BackupInspection.Valid>(it).backup.header.reason })
        val after = assertIs<ServerRuntimeState.Running>(clean.state.value)
        assertEquals(running.peerId, after.peerId)
        assertEquals(result, clean.realtime!!.bouts.bout(sessionId)!!.result)
        assertEquals(SessionState.COMPLETED, clean.realtime!!.bouts.bout(sessionId)!!.state)

        // The restored database stays active after a restart.
        clean.stop()
        assertEquals(running.peerId, assertIs<ServerRuntimeState.Running>(clean.start()).peerId)
        assertEquals(result, clean.realtime!!.bouts.bout(sessionId)!!.result)
        assertEquals(restored.database, Files.readString(root.resolve("clean-computer/database")))
    }

    @Test
    fun `a judged competition exports the same XLSX and CSV after a restore on a clean computer`() {
        val generatedAt = Instant.parse("2026-11-08T16:00:00Z")
        val source = runtime(freePort())
        assertIs<ServerRuntimeState.Running>(source.start())
        CompetitionScenario(
            requireNotNull(source.competitionApplications), requireNotNull(source.competitionDraws), requireNotNull(source.courtBouts),
            source.pairingRequests,
        ).apply {
            importAndDraw()
            connectJudges()
            judgeEverything()
        }
        val reports = requireNotNull(source.reports)
        val before = requireNotNull(reports.report(generatedAt))
        assertTrue(before.categories.all { it.places.isNotEmpty() })
        val csv = String(requireNotNull(reports.csv(generatedAt)))
        val xlsx = CompetitionReportTest.cells(requireNotNull(reports.xlsx(generatedAt)))
        // `BAK-002`: every completed bout and performance left a backup; the latest holds the whole competition.
        val sessions = before.sessions.size
        val automatic = awaitValue { requireNotNull(source.backups).list().filter { it.header?.reason == JournalBackups.AFTER_SESSION }.takeIf { it.size == sessions } }
        val usb = root.resolve("usb/competition.jsonl").also { requireNotNull(source.backups).copy(automatic.first().path, it) }
        source.stop()

        val clean = runtime(freePort(), data = "clean-computer")
        assertIs<ServerRuntimeState.Running>(clean.start())
        assertEquals(null, requireNotNull(clean.reports).report(generatedAt))
        assertIs<RestoreOutcome.Restored>(clean.restore(usb))
        val restored = requireNotNull(clean.reports)
        assertEquals(before, restored.report(generatedAt))
        assertEquals(csv, String(requireNotNull(restored.csv(generatedAt))))
        assertEquals(xlsx, CompetitionReportTest.cells(requireNotNull(restored.xlsx(generatedAt))))
        // The restored projections accept no second run of a decided bout: the court panel is empty.
        assertEquals(null, requireNotNull(clean.courtBouts).panel().next)
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

    private fun runtime(port: Int, data: String = "application-data"): ServerRuntime {
        val installationDirectory = System.getProperty("uJudge.postgres.installationDirectory")
        assumeTrue(!installationDirectory.isNullOrBlank(), "Requires the PostgreSQL bundle prepared by Gradle")
        return ServerRuntime(
            ServerRuntimeConfiguration(Path.of(installationDirectory), root.resolve(data), port, "JudgeServer-test"),
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

    private companion object {
        const val RESYNC_REQUEST = """{"type":"resync_request","requestId":"1e2d3c4b-5a69-4788-9a0b-c1d2e3f4a5b6","cursor":null}"""
    }

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

    private fun <T : Any> awaitValue(read: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(50)
        }
        error("The value did not appear in time")
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
        val closeReason = CompletableFuture<String>()

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closeReason.complete(reason)
            return null
        }

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
