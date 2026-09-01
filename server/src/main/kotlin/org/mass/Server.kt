package org.mass

import com.appstractive.dnssd.publishService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Metadata a mobile client uses to validate a server before pairing. */
@Serializable
data class ServerMetadata(
    val protocolVersion: String,
    val capabilities: Map<String, Boolean>,
    val peerId: String,
    val courtId: String,
    val serverName: String,
    val pairingPolicy: String,
    val serverTime: String,
) {
    companion object {
        fun local() = ServerMetadata(
            protocolVersion = "1.0",
            capabilities = mapOf("metadata" to true),
            peerId = "peer-local",
            courtId = "court-local",
            serverName = "U'Judge Server",
            pairingPolicy = "operator-approval",
            serverTime = Instant.now().toString(),
        )
    }
}

@Serializable
data class PairingRequestCommand(
    val deviceId: String,
    val surname: String,
    val platform: String,
)

@Serializable
data class PendingPairingRequest(
    val requestId: String,
    val deviceId: String,
    val surname: String,
    val platform: String,
    val state: String = "pending",
)

@Serializable
data class AcceptedPairingRequest(
    val requestId: String,
    val deviceId: String,
    val surname: String,
    val platform: String,
    val reconnectCredential: String,
    val state: String = "accepted",
)

@Serializable
data class RevokedPairingRequest(
    val requestId: String,
    val deviceId: String,
    val surname: String,
    val platform: String,
    val reconnectCredential: String,
    val state: String = "revoked",
)

@Serializable
data class PairingRequestError(val code: String)

/** Retains pending judge devices and local operator approval decisions. */
class PairingRequests {
    private val pendingByDeviceId = mutableMapOf<String, PendingPairingRequest>()
    private val acceptedByRequestId = mutableMapOf<String, AcceptedPairingRequest>()
    private val revokedByRequestId = mutableMapOf<String, RevokedPairingRequest>()

    fun submit(command: PairingRequestCommand): PairingSubmission = synchronized(this) {
        val deviceId = command.deviceId.trim()
        val surname = command.surname.trim()
        val platform = command.platform.lowercase()
        if (deviceId.isEmpty() || surname.isEmpty() || platform !in setOf("android", "ios")) {
            return PairingSubmission.Rejected
        }

        val existing = pendingByDeviceId[deviceId]
        if (existing != null) {
            return PairingSubmission.Pending(existing, created = false)
        }

        val request = PendingPairingRequest(
            requestId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            surname = surname,
            platform = platform,
        )
        pendingByDeviceId[deviceId] = request
        PairingSubmission.Pending(request, created = true)
    }

    fun pending(): List<PendingPairingRequest> = synchronized(this) {
        pendingByDeviceId.values.toList()
    }

    fun approve(requestId: String): PairingApproval = synchronized(this) {
        acceptedByRequestId[requestId]?.let {
            return PairingApproval.Accepted(it, created = false)
        }
        val pendingEntry = pendingByDeviceId.entries.firstOrNull { it.value.requestId == requestId }
            ?: return PairingApproval.UnknownRequest
        pendingByDeviceId.remove(pendingEntry.key)
        val pending = pendingEntry.value
        val accepted = AcceptedPairingRequest(
            requestId = pending.requestId,
            deviceId = pending.deviceId,
            surname = pending.surname,
            platform = pending.platform,
            reconnectCredential = newReconnectCredential(),
        )
        acceptedByRequestId[requestId] = accepted
        PairingApproval.Accepted(accepted, created = true)
    }

    fun revoke(requestId: String): PairingRevocation = synchronized(this) {
        revokedByRequestId[requestId]?.let {
            return PairingRevocation.Revoked(it, created = false)
        }
        val accepted = acceptedByRequestId[requestId] ?: return PairingRevocation.UnknownRequest
        val revoked = RevokedPairingRequest(
            requestId = accepted.requestId,
            deviceId = accepted.deviceId,
            surname = accepted.surname,
            platform = accepted.platform,
            reconnectCredential = accepted.reconnectCredential,
        )
        revokedByRequestId[requestId] = revoked
        PairingRevocation.Revoked(revoked, created = true)
    }

    fun isReconnectCredentialActive(reconnectCredential: String): Boolean = synchronized(this) {
        acceptedByRequestId.values.any { accepted ->
            accepted.reconnectCredential == reconnectCredential && accepted.requestId !in revokedByRequestId
        }
    }

    private fun newReconnectCredential(): String = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
}

sealed interface PairingSubmission {
    data class Pending(val request: PendingPairingRequest, val created: Boolean) : PairingSubmission

    data object Rejected : PairingSubmission
}

sealed interface PairingApproval {
    data class Accepted(val request: AcceptedPairingRequest, val created: Boolean) : PairingApproval

    data object UnknownRequest : PairingApproval
}

sealed interface PairingRevocation {
    data class Revoked(val request: RevokedPairingRequest, val created: Boolean) : PairingRevocation

    data object UnknownRequest : PairingRevocation
}

/** Configures the HTTP protocol exposed by the local court server. */
fun Application.module(
    metadata: ServerMetadata = ServerMetadata.local(),
    pairingRequests: PairingRequests = PairingRequests(),
) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respondText("JudgeServer OK")
        }
        get("/v1/metadata") {
            call.respond(metadata)
        }
        post("/v1/pairing-requests") {
            when (val submission = pairingRequests.submit(call.receive())) {
                is PairingSubmission.Pending -> {
                    call.respond(
                        if (submission.created) HttpStatusCode.Accepted else HttpStatusCode.OK,
                        submission.request,
                    )
                }
                PairingSubmission.Rejected -> {
                    call.respond(HttpStatusCode.BadRequest, PairingRequestError("invalid_pairing_request"))
                }
            }
        }
        get("/v1/pairing-requests") {
            call.respond(pairingRequests.pending())
        }
    }
}

fun main() {
    Server.start()
    Runtime.getRuntime().addShutdownHook(Thread(Server::stop))
    Thread.currentThread().join()
}

object Server {
    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var mdnsScope: CoroutineScope? = null
    fun start() {
        ktorServer = embeddedServer(
            CIO,
            port = 8080,
            host = "0.0.0.0",
        ) { module() }.start(wait = false)

        mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mdnsScope?.launch {
            publishService(type = "_u-judge._tcp", name = "JudgeServer-1") {
                port = 8080
            }
        }
    }

    fun stop() {
        ktorServer?.stop(1000, 5000)
        mdnsScope?.cancel()
        ktorServer = null
        mdnsScope = null
    }
}
