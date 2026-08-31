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
data class PairingRequestError(val code: String)

/** Retains unapproved judge devices until the operator approval slice is available. */
class PairingRequests {
    private val pendingByDeviceId = mutableMapOf<String, PendingPairingRequest>()

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
}

sealed interface PairingSubmission {
    data class Pending(val request: PendingPairingRequest, val created: Boolean) : PairingSubmission

    data object Rejected : PairingSubmission
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
