package org.mass

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.io.readByteArray
import java.time.Duration
import java.time.Instant
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.system.exitProcess

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
        fun local(peerId: String = "peer-local") = ServerMetadata(
            protocolVersion = "1.0",
            capabilities = mapOf("metadata" to true, "kerugi_bout" to true, "tanbon_bout" to true, "technical_performance" to true),
            peerId = peerId,
            courtId = "court-local",
            serverName = "U'Judge Server",
            pairingPolicy = "operator-approval",
            serverTime = Instant.now().toString(),
        )
    }
}

/** Read-only liveness status for local operator diagnostics. */
@Serializable
data class HealthStatus(val status: String = "healthy")

@Serializable
data class PairingRequestCommand(
    val deviceId: String,
    val surname: String,
    val platform: String,
    val deliveryProof: String? = null,
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
    val state: String = "revoked",
)

@Serializable
data class PairingRequestError(val code: String)

@Serializable
enum class PairingStatusState {
    @kotlinx.serialization.SerialName("pending")
    PENDING,

    @kotlinx.serialization.SerialName("accepted")
    ACCEPTED,

    @kotlinx.serialization.SerialName("rejected")
    REJECTED,
}

@Serializable
enum class PairingStatusCode {
    @kotlinx.serialization.SerialName("operator_rejected")
    OPERATOR_REJECTED,

    /** The same device sent a newer request, which replaced this one. */
    @kotlinx.serialization.SerialName("superseded")
    SUPERSEDED,
}

@Serializable
data class PairingStatus(
    val type: String = "pairing_status",
    val state: PairingStatusState,
    val deviceId: String,
    val code: PairingStatusCode? = null,
    val reconnectCredential: String? = null,
) {
    init {
        require(type == "pairing_status")
        require((state == PairingStatusState.REJECTED) == (code != null))
        require(reconnectCredential == null || state == PairingStatusState.ACCEPTED)
    }
}

const val PAIRING_DELIVERY_PROOF_HEADER = "X-UJudge-Pairing-Delivery-Proof"

@Serializable
enum class DeviceConnectionState {
    @kotlinx.serialization.SerialName("connected")
    CONNECTED,

    @kotlinx.serialization.SerialName("disconnected")
    DISCONNECTED,
}

@Serializable
data class OperatorDeviceConnection(
    val deviceId: String,
    val platform: String,
    val connectionState: DeviceConnectionState,
)

@Serializable
data class RealtimeHandshakeRequest(
    val type: String,
    val protocolVersion: String,
    val reconnectCredential: String,
)

@Serializable
data class RealtimeHandshakeAccepted(val type: String)

@Serializable
data class RealtimeHandshakeRejected(val type: String, val code: String)

@Serializable
data class ClockSyncRequest(
    val type: String,
    val clientSendTimestamp: String,
)

@Serializable
data class ClockSyncResponse(
    val type: String,
    val clientSendTimestamp: String,
    val serverReceiveTimestamp: String,
    val serverSendTimestamp: String,
)

@Serializable
data class ClockSyncRejected(val type: String, val code: String)

@Serializable
data class HeartbeatRequest(val type: String) {
    init {
        require(type == "heartbeat")
    }
}

@Serializable
data class HeartbeatAcknowledgement(val type: String)

@Serializable
data class HeartbeatRejected(val type: String, val code: String)

/**
 * Pending judge devices and local operator decisions. Every decision is appended to [journal] before it takes effect and
 * the registry is rebuilt from it on start, so approvals and revocations survive a restart. Reconnect credentials and
 * delivery proofs are kept as SHA-256 hashes; a plaintext credential exists only in memory until it is delivered.
 */
class PairingRequests(private val journal: DeviceRegistryJournal = DeviceRegistryJournal.InMemory) {
    private val devicesByRequestId = linkedMapOf<String, RegisteredDevice>()
    private val deliverableCredentials = mutableMapOf<String, String>()
    private val connectionsByCredentialHash = mutableMapOf<String, Int>()
    private val mutableChanges = MutableStateFlow(0L)

    /** Increments on every registry or connection change so the operator UI can refresh its projection. */
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    init {
        journal.events().forEach(::applyEvent)
    }

    fun submit(command: PairingRequestCommand): PairingSubmission = synchronized(this) {
        val deviceId = command.deviceId.trim()
        val surname = command.surname.trim()
        val platform = command.platform.lowercase()
        val deliveryProof = command.deliveryProof
        if (deviceId.isEmpty() || deviceId.length > MAX_FIELD_LENGTH || surname.isEmpty() ||
            surname.length > MAX_FIELD_LENGTH || platform !in setOf("android", "ios") ||
            (deliveryProof != null && (deliveryProof.isBlank() || deliveryProof.length > 512))) {
            return PairingSubmission.Rejected
        }

        val existing = devicesByRequestId.values.firstOrNull { it.deviceId == deviceId && it.state == DeviceState.PENDING }
        if (existing != null) {
            val sameProof = deliveryProof == null || existing.deliveryProofHash == hashHex(deliveryProof)
            if (sameProof) return PairingSubmission.Pending(existing.pending(), created = false)
            // The device lost its earlier proof (app restarted before approval): the old request can never receive the
            // credential, so the newer one replaces it and the operator still approves it by the verification code.
            record(deviceId, DeviceRegistryEvent.Superseded(existing.requestId))
        }

        val event = DeviceRegistryEvent.Requested(
            requestId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            surname = surname,
            platform = platform,
            deliveryProofHash = deliveryProof?.let(::hashHex),
        )
        record(deviceId, event)
        PairingSubmission.Pending(devicesByRequestId.getValue(event.requestId).pending(), created = true)
    }

    fun pending(): List<PendingPairingRequest> = synchronized(this) {
        devicesByRequestId.values.filter { it.state == DeviceState.PENDING }.map { it.pending() }
    }

    fun approve(requestId: String): PairingApproval = synchronized(this) {
        val device = devicesByRequestId[requestId] ?: return PairingApproval.UnknownRequest
        when (device.state) {
            DeviceState.ACCEPTED, DeviceState.REVOKED ->
                PairingApproval.Accepted(device.accepted(deliverableCredential(device)), created = false)
            DeviceState.PENDING -> {
                val credential = newReconnectCredential()
                record(device.deviceId, DeviceRegistryEvent.Approved(requestId, hashHex(credential)))
                deliverableCredentials[requestId] = credential
                PairingApproval.Accepted(device.accepted(credential), created = true)
            }
            DeviceState.REJECTED -> PairingApproval.UnknownRequest
        }
    }

    fun reject(requestId: String): PairingRejection = synchronized(this) {
        val device = devicesByRequestId[requestId] ?: return PairingRejection.UnknownRequest
        when (device.state) {
            DeviceState.REJECTED -> PairingRejection.Rejected(device.rejectedStatus(), created = false)
            DeviceState.PENDING -> {
                record(device.deviceId, DeviceRegistryEvent.Rejected(requestId))
                PairingRejection.Rejected(device.rejectedStatus(), created = true)
            }
            else -> PairingRejection.UnknownRequest
        }
    }

    fun revoke(requestId: String): PairingRevocation = synchronized(this) {
        val device = devicesByRequestId[requestId] ?: return PairingRevocation.UnknownRequest
        when (device.state) {
            DeviceState.REVOKED -> PairingRevocation.Revoked(device.revoked(), created = false)
            DeviceState.ACCEPTED -> {
                record(device.deviceId, DeviceRegistryEvent.Revoked(requestId))
                deliverableCredentials.remove(requestId)
                device.credentialHash?.let(connectionsByCredentialHash::remove)
                PairingRevocation.Revoked(device.revoked(), created = true)
            }
            else -> PairingRevocation.UnknownRequest
        }
    }

    /**
     * Revokes every accepted device (the operator's «reset all devices»): each one is its own revocation event, exactly as
     * if revoked one by one, so its socket closes and its new events are rejected. Returns how many were revoked.
     */
    fun revokeAll(): Int = synchronized(this) {
        devicesByRequestId.values
            .filter { it.state == DeviceState.ACCEPTED }
            .map { it.requestId }
            .count { requestId -> (revoke(requestId) as? PairingRevocation.Revoked)?.created == true }
    }

    fun isReconnectCredentialActive(reconnectCredential: String): Boolean = synchronized(this) {
        activeDevice(reconnectCredential) != null
    }

    fun deviceIdFor(reconnectCredential: String): String? = synchronized(this) {
        val credentialHash = hashHex(reconnectCredential)
        devicesByRequestId.values.firstOrNull { it.credentialHash == credentialHash }?.deviceId
    }

    fun connected(reconnectCredential: String) = synchronized(this) {
        val device = activeDevice(reconnectCredential) ?: return@synchronized
        connectionsByCredentialHash.merge(requireNotNull(device.credentialHash), 1, Int::plus)
        mutableChanges.value++
    }

    fun disconnected(reconnectCredential: String) = synchronized(this) {
        val credentialHash = hashHex(reconnectCredential)
        val connections = connectionsByCredentialHash[credentialHash] ?: return@synchronized
        if (connections == 1) {
            connectionsByCredentialHash.remove(credentialHash)
        } else {
            connectionsByCredentialHash[credentialHash] = connections - 1
        }
        mutableChanges.value++
    }

    fun operatorDevices(): List<OperatorDeviceConnection> = synchronized(this) {
        devicesByRequestId.values
            .filter { it.state == DeviceState.ACCEPTED }
            .map { OperatorDeviceConnection(it.deviceId, it.platform, connectionState(it)) }
            .sortedBy(OperatorDeviceConnection::deviceId)
    }

    /** In-process projection for the desktop operator, who needs the surname to recognise the judge. */
    fun operatorRegistry(): OperatorDeviceRegistry = synchronized(this) {
        OperatorDeviceRegistry(
            pending = pending(),
            devices = devicesByRequestId.values
                .filter { it.state == DeviceState.ACCEPTED || it.state == DeviceState.REVOKED }
                .map {
                    OperatorDevice(
                        requestId = it.requestId,
                        deviceId = it.deviceId,
                        surname = it.surname,
                        platform = it.platform,
                        connectionState = if (it.state == DeviceState.REVOKED) null else connectionState(it),
                        revoked = it.state == DeviceState.REVOKED,
                    )
                },
        )
    }

    fun status(requestId: String, deliveryProof: String? = null, secureDelivery: Boolean = false): PairingStatus? = synchronized(this) {
        val device = devicesByRequestId[requestId] ?: return null
        when (device.state) {
            DeviceState.PENDING -> PairingStatus(state = PairingStatusState.PENDING, deviceId = device.deviceId)
            DeviceState.REJECTED -> device.rejectedStatus()
            DeviceState.ACCEPTED, DeviceState.REVOKED -> {
                val credential = if (device.state == DeviceState.ACCEPTED && secureDelivery && deliveryProof != null &&
                    device.deliveryProofHash == hashHex(deliveryProof)) {
                    deliverableCredential(device)
                } else {
                    null
                }
                PairingStatus(state = PairingStatusState.ACCEPTED, deviceId = device.deviceId, reconnectCredential = credential)
            }
        }
    }

    /**
     * The plaintext credential of this process, or a newly issued one when it was lost with a previous process; issuing
     * invalidates the credential the device could not have received.
     */
    private fun deliverableCredential(device: RegisteredDevice): String {
        deliverableCredentials[device.requestId]?.let { return it }
        val credential = newReconnectCredential()
        record(device.deviceId, DeviceRegistryEvent.CredentialIssued(device.requestId, hashHex(credential)))
        deliverableCredentials[device.requestId] = credential
        return credential
    }

    private fun activeDevice(reconnectCredential: String): RegisteredDevice? {
        val credentialHash = hashHex(reconnectCredential)
        return devicesByRequestId.values.firstOrNull { it.state == DeviceState.ACCEPTED && it.credentialHash == credentialHash }
    }

    private fun connectionState(device: RegisteredDevice) =
        if (device.credentialHash in connectionsByCredentialHash) DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED

    private fun record(deviceId: String, event: DeviceRegistryEvent) {
        journal.append(deviceId, event)
        applyEvent(event)
        ServerLog.deviceRegistryChanged(event::class.simpleName.orEmpty(), deviceId)
    }

    private fun applyEvent(event: DeviceRegistryEvent) {
        when (event) {
            is DeviceRegistryEvent.Requested -> devicesByRequestId[event.requestId] = RegisteredDevice(
                requestId = event.requestId,
                deviceId = event.deviceId,
                surname = event.surname,
                platform = event.platform,
                deliveryProofHash = event.deliveryProofHash,
            )
            is DeviceRegistryEvent.Approved -> devicesByRequestId[event.requestId]?.let {
                it.state = DeviceState.ACCEPTED
                it.credentialHash = event.credentialHash
            }
            is DeviceRegistryEvent.CredentialIssued -> devicesByRequestId[event.requestId]?.credentialHash = event.credentialHash
            is DeviceRegistryEvent.Rejected -> devicesByRequestId[event.requestId]?.state = DeviceState.REJECTED
            is DeviceRegistryEvent.Superseded -> devicesByRequestId[event.requestId]?.let {
                it.state = DeviceState.REJECTED
                it.rejectionCode = PairingStatusCode.SUPERSEDED
            }
            is DeviceRegistryEvent.Revoked -> devicesByRequestId[event.requestId]?.state = DeviceState.REVOKED
        }
        mutableChanges.value++
    }

    private fun newReconnectCredential(): String = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun hashHex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private enum class DeviceState { PENDING, ACCEPTED, REJECTED, REVOKED }

    private class RegisteredDevice(
        val requestId: String,
        val deviceId: String,
        val surname: String,
        val platform: String,
        val deliveryProofHash: String?,
    ) {
        var state = DeviceState.PENDING
        var credentialHash: String? = null
        var rejectionCode = PairingStatusCode.OPERATOR_REJECTED

        fun pending() = PendingPairingRequest(requestId, deviceId, surname, platform)

        fun accepted(credential: String) = AcceptedPairingRequest(requestId, deviceId, surname, platform, credential)

        fun revoked() = RevokedPairingRequest(requestId, deviceId, surname, platform)

        fun rejectedStatus() = PairingStatus(
            state = PairingStatusState.REJECTED,
            deviceId = deviceId,
            code = rejectionCode,
        )
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 255
    }
}

/** Desktop operator view of the device registry. */
data class OperatorDeviceRegistry(
    val pending: List<PendingPairingRequest>,
    val devices: List<OperatorDevice>,
)

data class OperatorDevice(
    val requestId: String,
    val deviceId: String,
    val surname: String,
    val platform: String,
    /** Null for a revoked device. */
    val connectionState: DeviceConnectionState?,
    val revoked: Boolean,
)

sealed interface PairingSubmission {
    data class Pending(val request: PendingPairingRequest, val created: Boolean) : PairingSubmission

    data object Rejected : PairingSubmission
}

sealed interface PairingApproval {
    data class Accepted(val request: AcceptedPairingRequest, val created: Boolean) : PairingApproval

    data object UnknownRequest : PairingApproval
}

sealed interface PairingRejection {
    data class Rejected(val status: PairingStatus, val created: Boolean) : PairingRejection

    data object UnknownRequest : PairingRejection
}

sealed interface PairingRevocation {
    data class Revoked(val request: RevokedPairingRequest, val created: Boolean) : PairingRevocation

    data object UnknownRequest : PairingRevocation
}

/** Configures the HTTP protocol exposed by the local court server. */
fun Application.module(
    metadata: ServerMetadata = ServerMetadata.local(),
    pairingRequests: PairingRequests = PairingRequests(),
    realtime: RealtimeHub = RealtimeHub.inMemory(),
    heartbeatTimeout: Duration = Duration.ofSeconds(30),
    credentialDeliveryIsSecure: (ApplicationCall) -> Boolean = { call -> call.request.local.scheme == "https" },
    clock: () -> Instant = Instant::now,
) {
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            explicitNulls = false
        })
    }
    install(WebSockets) {
        // A larger frame closes the socket (1009) before it is buffered whole; up to this size it gets a typed rejection.
        maxFrameSize = MAX_REALTIME_FRAME_BYTES
    }

    routing {
        get("/") {
            call.respondText("JudgeServer OK")
        }
        get("/v1/health") {
            call.respond(HealthStatus())
        }
        get("/v1/metadata") {
            call.respond(metadata.copy(serverTime = clock().toString()))
        }
        post("/v1/pairing-requests") {
            // The only anonymous write: bounded and decoded here, before the pairing service sees it (`NFR-007`).
            val body = call.receiveBoundedText(MAX_PAIRING_REQUEST_BYTES)
            if (body == null) {
                call.respond(HttpStatusCode.PayloadTooLarge, PairingRequestError("pairing_request_too_large"))
                return@post
            }
            val command = runCatching { Json.decodeFromString<PairingRequestCommand>(body) }.getOrNull()
            if (command == null) {
                call.respond(HttpStatusCode.BadRequest, PairingRequestError("invalid_pairing_request"))
                return@post
            }
            when (val submission = pairingRequests.submit(command)) {
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
        get("/v1/pairing-status/{requestId}") {
            val requestId = requireNotNull(call.parameters["requestId"])
            val status = pairingRequests.status(
                requestId,
                call.request.headers[PAIRING_DELIVERY_PROOF_HEADER],
                credentialDeliveryIsSecure(call),
            )
            if (status == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(status)
            }
        }
        webSocket("/v1/realtime") {
            val handshake = (incoming.receiveCatching().getOrNull() as? Frame.Text)
                ?.readText()
                ?.let { runCatching { Json.decodeFromString<RealtimeHandshakeRequest>(it) }.getOrNull() }
            val rejectionCode = when {
                handshake == null || handshake.type != "handshake" -> "invalid_handshake"
                handshake.protocolVersion != metadata.protocolVersion -> "unsupported_protocol_version"
                !pairingRequests.isReconnectCredentialActive(handshake.reconnectCredential) -> "invalid_reconnect_credential"
                else -> null
            }
            if (rejectionCode != null) {
                ServerLog.handshakeRejected(rejectionCode)
                send(Frame.Text(Json.encodeToString(RealtimeHandshakeRejected("handshake_rejected", rejectionCode))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, rejectionCode))
                return@webSocket
            }
            val reconnectCredential = requireNotNull(handshake).reconnectCredential
            val deviceId = pairingRequests.deviceIdFor(reconnectCredential)
            pairingRequests.connected(reconnectCredential)
            ServerLog.deviceConnected(deviceId)
            val heartbeatTracker = RealtimeHeartbeatTracker(heartbeatTimeout)
            heartbeatTracker.connected(reconnectCredential)
            send(Frame.Text(Json.encodeToString(RealtimeHandshakeAccepted("handshake_accepted"))))
            // Every later frame goes through one queue, so replies and pushes keep the journal order (ADR-009 §1.6).
            val outbound = Channel<String>(Channel.UNLIMITED)
            val connection = RealtimeConnection(deviceId.orEmpty(), outbound)
            val writer = launch {
                runCatching { for (text in outbound) send(Frame.Text(text)) }
            }
            fun scheduleHeartbeatTimeout() = launch {
                delay(heartbeatTimeout.toMillis())
                if (reconnectCredential in heartbeatTracker.expireInactive()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "heartbeat_timeout"))
                }
            }
            var timeoutJob = scheduleHeartbeatTimeout()
            // Revocation ends the session at once instead of waiting for the device's next message (`DEV-006`).
            val revocationJob = launch {
                pairingRequests.changes.first { !pairingRequests.isReconnectCredentialActive(reconnectCredential) }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "credential_revoked"))
            }
            try {
                while (true) {
                    val frame = incoming.receiveCatching().getOrNull() ?: break
                    val commandText = when (frame) {
                        is Frame.Text -> frame.readText()
                        is Frame.Close -> break
                        else -> continue
                    }
                    val message = if (commandText.length > MAX_REALTIME_MESSAGE_LENGTH) null else runCatching {
                        Json.parseToJsonElement(commandText) as? JsonObject
                    }.getOrNull()
                    val messageEventId = (message?.get("eventId") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    if (!pairingRequests.isReconnectCredentialActive(reconnectCredential)) {
                        ServerLog.commandRejected(deviceId, "invalid_reconnect_credential")
                        connection.send(
                            realtimeJson.encodeToString(
                                RealtimeCommandRejected("command_rejected", "invalid_reconnect_credential", messageEventId),
                            ),
                        )
                        continue
                    }
                    if (commandText.length > MAX_REALTIME_MESSAGE_LENGTH) {
                        connection.send(realtimeJson.encodeToString(RealtimeCommandRejected("command_rejected", "command_too_large", null)))
                        continue
                    }
                    val serverReceiveTimestamp = Instant.now().toString()
                    val messageType = (message?.get("type") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    if (messageType == "clock_sync") {
                        val clockSync = runCatching { Json.decodeFromString<ClockSyncRequest>(commandText) }.getOrNull()
                        if (clockSync == null || runCatching { Instant.parse(clockSync.clientSendTimestamp) }.isFailure) {
                            connection.send(Json.encodeToString(ClockSyncRejected("clock_sync_rejected", "invalid_clock_sync_timestamp")))
                        } else {
                            connection.send(
                                Json.encodeToString(
                                    ClockSyncResponse(
                                        type = "clock_sync_response",
                                        clientSendTimestamp = clockSync.clientSendTimestamp,
                                        serverReceiveTimestamp = serverReceiveTimestamp,
                                        serverSendTimestamp = Instant.now().toString(),
                                    ),
                                ),
                            )
                        }
                        continue
                    }
                    if (messageType == "heartbeat") {
                        val heartbeat = runCatching { Json.decodeFromString<HeartbeatRequest>(commandText) }.getOrNull()
                        if (heartbeat == null) {
                            connection.send(Json.encodeToString(HeartbeatRejected("heartbeat_rejected", "invalid_heartbeat")))
                        } else {
                            heartbeatTracker.heartbeat(reconnectCredential)
                            timeoutJob.cancel()
                            timeoutJob = scheduleHeartbeatTimeout()
                            connection.send(Json.encodeToString(HeartbeatAcknowledgement("heartbeat_ack")))
                        }
                        continue
                    }
                    realtime.receive(connection, commandText, messageType, messageEventId)
                }
            } finally {
                realtime.leave(connection)
                outbound.close()
                timeoutJob.cancel()
                revocationJob.cancel()
                heartbeatTracker.disconnected(reconnectCredential)
                pairingRequests.disconnected(reconnectCredential)
                ServerLog.deviceDisconnected(deviceId)
                writer.join()
            }
        }
    }
}

/** Client messages above this length are rejected before decoding (ADR-004). */
const val MAX_REALTIME_MESSAGE_LENGTH = 4_096

/** WebSocket frames above this size close the connection instead of being read into memory (`NFR-007`). */
const val MAX_REALTIME_FRAME_BYTES = 64L * 1024

/** A pairing request body above this size is refused unread (`NFR-007`); its fields are at most about 1 KB. */
const val MAX_PAIRING_REQUEST_BYTES = 4_096

/** The body as UTF-8, or null when it is larger than [limit] bytes; never reads more than [limit] + 1 bytes. */
private suspend fun ApplicationCall.receiveBoundedText(limit: Int): String? {
    if ((request.contentLength() ?: 0) > limit) return null
    val bytes = receiveChannel().readRemaining(limit + 1L).readByteArray()
    return if (bytes.size > limit) null else bytes.decodeToString()
}

fun main(args: Array<String>) {
    ServerSmokeCheck.options(args)?.let { exitProcess(ServerSmokeCheck.run(it)) }
    ServerLog.useDirectory(ServerRuntimeConfiguration.fromEnvironment().applicationDataDirectory.resolve("logs"))
    when (val state = Server.start()) {
        is ServerRuntimeState.Running -> println(
            "U'Judge server peer ${state.peerId.value} listens on https://0.0.0.0:${state.port}, " +
                "verification code ${state.verificationCode}",
        )
        else -> System.err.println("U'Judge server did not start: $state")
    }
    Runtime.getRuntime().addShutdownHook(Thread(Server::stop))
    Thread.currentThread().join()
}

/** Production entry point shared by the desktop application and `:server:run`. */
object Server {
    val runtime: ServerRuntime by lazy { ServerRuntime(ServerRuntimeConfiguration.fromEnvironment()) }

    fun start(): ServerRuntimeState = runtime.start()

    fun stop() = runtime.stop()
}
