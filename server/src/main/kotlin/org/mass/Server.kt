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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mass.replication.JdbcPeerJournal
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.DeviceId
import org.mass.domain.DomainCommand
import org.mass.domain.EventId
import org.mass.domain.EventSource
import org.mass.domain.JudgeId
import org.mass.domain.KERUGI_SCORE_CANDIDATE_EVENT
import org.mass.domain.KERUGI_OPERATOR_ACTION_EVENT
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionPayload
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoreCandidatePayload
import org.mass.domain.KerugiScoreEventJournal
import org.mass.domain.KerugiScoreResult
import org.mass.domain.KerugiScoringArea
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SessionLifecycleEventJournal
import org.mass.domain.SessionLifecycleResult
import java.time.Duration
import java.time.Instant
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
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

/** Read-only liveness status for local operator diagnostics. */
@Serializable
data class HealthStatus(val status: String = "healthy")

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
}

@Serializable
data class PairingStatus(
    val type: String = "pairing_status",
    val state: PairingStatusState,
    val deviceId: String,
    val code: PairingStatusCode? = null,
) {
    init {
        require(type == "pairing_status")
        require((state == PairingStatusState.REJECTED) == (code != null))
    }
}

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

@Serializable
data class RealtimeCommandRequest(
    val type: String,
    val eventId: String,
    val sequence: Long,
    val clientTimestamp: String,
    val sessionId: String,
    val payload: JsonObject,
)

@Serializable
data class RealtimeCommandAcknowledgement(val type: String, val eventId: String)

@Serializable
data class RealtimeCommandRejected(val type: String, val code: String)

@Serializable
data class RealtimeSessionLifecycleCommandRequest(
    val type: String,
    val eventId: String,
    val competitionId: String,
    val peerId: String,
    val courtId: String,
    val bracketId: String,
    val sessionId: String,
    val judgeId: String,
    val deviceId: String,
    val source: String,
    val author: String,
    val eventType: String,
    val payload: String,
)

@Serializable
data class RealtimeSessionLifecycleAcknowledgement(val type: String, val eventId: String, val state: String)

@Serializable
data class RealtimeSessionLifecycleRejected(val type: String, val code: String)

@Serializable
data class RealtimeSessionStateUpdated(
    val type: String,
    val sessionId: String,
    val state: String,
)

@Serializable
data class RealtimeKerugiScoreCommandRequest(
    val type: String,
    val eventId: String,
    val competitionId: String,
    val peerId: String,
    val courtId: String,
    val bracketId: String,
    val sessionId: String,
    val judgeId: String,
    val deviceId: String,
    val source: String,
    val author: String,
    val competitor: KerugiCompetitor,
    val area: KerugiScoringArea,
    val occurredAt: String,
)

@Serializable
data class RealtimeKerugiOperatorActionCommandRequest(
    val type: String,
    val eventId: String,
    val competitionId: String,
    val peerId: String,
    val courtId: String,
    val bracketId: String,
    val sessionId: String,
    val judgeId: String,
    val deviceId: String,
    val source: String,
    val author: String,
    val action: KerugiOperatorActionType,
    val competitor: KerugiCompetitor,
    val points: Int,
)

@Serializable
data class RealtimeKerugiScoreAcknowledgement(val type: String, val eventId: String, val blueScore: Int, val redScore: Int)

@Serializable
data class RealtimeKerugiScoreRejected(val type: String, val code: String)

@Serializable
data class RealtimeKerugiScoreUpdated(
    val type: String,
    val sessionId: String,
    val blueScore: Int,
    val redScore: Int,
)

class RealtimeKerugiScoreCommands(private val journal: KerugiScoreEventJournal) {
    fun accept(request: RealtimeKerugiScoreCommandRequest): RealtimeKerugiScoreOutcome {
        val eventId = try {
            require(request.type == "kerugi_score_command")
            val occurredAt = Instant.parse(request.occurredAt)
            val payload = Json.encodeToString(KerugiScoreCandidatePayload(request.competitor, request.area, occurredAt.toString()))
            val command = DomainCommand(
                CompetitionId(request.competitionId), PeerId(request.peerId), CourtId(request.courtId), BracketId(request.bracketId),
                SessionId(request.sessionId), JudgeId(request.judgeId), DeviceId(request.deviceId), EventSource(request.source),
                request.author, KERUGI_SCORE_CANDIDATE_EVENT, payload,
            )
            EventId(request.eventId) to command
        } catch (_: IllegalArgumentException) {
            return RealtimeKerugiScoreOutcome.Rejected("invalid_kerugi_score_command")
        }
        return try {
            when (val result = journal.apply(eventId.second, eventId.first)) {
                is KerugiScoreResult.Applied -> RealtimeKerugiScoreOutcome.Acknowledged(
                    RealtimeKerugiScoreAcknowledgement(
                        "kerugi_score_ack", result.event.event.eventId.value,
                        result.projection.blueScore, result.projection.redScore,
                    ),
                    result.event.event.sessionId.value,
                    result.isNew,
                )
                is KerugiScoreResult.Rejected -> RealtimeKerugiScoreOutcome.Rejected("kerugi_score_command_rejected")
            }
        } catch (_: Exception) {
            RealtimeKerugiScoreOutcome.Rejected("kerugi_score_unavailable")
        }
    }
}

class RealtimeKerugiOperatorActionCommands(private val journal: KerugiScoreEventJournal) {
    fun accept(request: RealtimeKerugiOperatorActionCommandRequest): RealtimeKerugiScoreOutcome {
        val eventId = try {
            require(request.type == "kerugi_operator_action_command")
            val payload = Json.encodeToString(KerugiOperatorActionPayload(request.action, request.competitor, request.points))
            val command = DomainCommand(
                CompetitionId(request.competitionId), PeerId(request.peerId), CourtId(request.courtId), BracketId(request.bracketId),
                SessionId(request.sessionId), JudgeId(request.judgeId), DeviceId(request.deviceId), EventSource(request.source),
                request.author, KERUGI_OPERATOR_ACTION_EVENT, payload,
            )
            EventId(request.eventId) to command
        } catch (_: IllegalArgumentException) {
            return RealtimeKerugiScoreOutcome.Rejected("invalid_kerugi_operator_action_command")
        }
        return try {
            when (val result = journal.apply(eventId.second, eventId.first)) {
                is KerugiScoreResult.Applied -> RealtimeKerugiScoreOutcome.Acknowledged(
                    RealtimeKerugiScoreAcknowledgement(
                        "kerugi_operator_action_ack", result.event.event.eventId.value,
                        result.projection.blueScore, result.projection.redScore,
                    ),
                    result.event.event.sessionId.value,
                    result.isNew,
                )
                is KerugiScoreResult.Rejected -> RealtimeKerugiScoreOutcome.Rejected("kerugi_operator_action_command_rejected")
            }
        } catch (_: Exception) {
            RealtimeKerugiScoreOutcome.Rejected("kerugi_operator_action_unavailable")
        }
    }
}

sealed interface RealtimeKerugiScoreOutcome {
    data class Acknowledged(
        val acknowledgement: RealtimeKerugiScoreAcknowledgement,
        val sessionId: String,
        val isNew: Boolean,
    ) : RealtimeKerugiScoreOutcome

    data class Rejected(val code: String) : RealtimeKerugiScoreOutcome
}

class RealtimeSessionStatePublisher {
    private val subscribers = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())

    fun subscribe(session: WebSocketSession) {
        subscribers += session
    }

    fun unsubscribe(session: WebSocketSession) {
        subscribers -= session
    }

    suspend fun publish(update: RealtimeSessionStateUpdated) {
        val recipients = synchronized(subscribers) { subscribers.toList() }
        recipients.forEach { session ->
            runCatching { session.send(Frame.Text(Json.encodeToString(update))) }
                .onFailure { unsubscribe(session) }
        }
    }
}

class RealtimeKerugiScorePublisher {
    private val subscribers = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())

    fun subscribe(session: WebSocketSession) {
        subscribers += session
    }

    fun unsubscribe(session: WebSocketSession) {
        subscribers -= session
    }

    suspend fun publish(update: RealtimeKerugiScoreUpdated) {
        val recipients = synchronized(subscribers) { subscribers.toList() }
        recipients.forEach { session ->
            runCatching { session.send(Frame.Text(Json.encodeToString(update))) }
                .onFailure { unsubscribe(session) }
        }
    }
}

class RealtimeSessionLifecycleCommands(private val journal: SessionLifecycleEventJournal) {
    fun accept(request: RealtimeSessionLifecycleCommandRequest): RealtimeSessionLifecycleOutcome {
        val command = try {
            require(request.type == "session_lifecycle_command")
            DomainCommand(
                competitionId = CompetitionId(request.competitionId),
                peerId = PeerId(request.peerId),
                courtId = CourtId(request.courtId),
                bracketId = BracketId(request.bracketId),
                sessionId = SessionId(request.sessionId),
                judgeId = JudgeId(request.judgeId),
                deviceId = DeviceId(request.deviceId),
                source = EventSource(request.source),
                author = request.author,
                type = request.eventType,
                payload = request.payload,
            )
        } catch (_: IllegalArgumentException) {
            return RealtimeSessionLifecycleOutcome.Rejected("invalid_lifecycle_command")
        }
        val eventId = try {
            EventId(request.eventId)
        } catch (_: IllegalArgumentException) {
            return RealtimeSessionLifecycleOutcome.Rejected("invalid_lifecycle_command")
        }
        val result = try {
            journal.apply(command, eventId)
        } catch (_: Exception) {
            return RealtimeSessionLifecycleOutcome.Rejected("lifecycle_unavailable")
        }
        return when (result) {
            is SessionLifecycleResult.Applied -> RealtimeSessionLifecycleOutcome.Acknowledged(
                RealtimeSessionLifecycleAcknowledgement(
                    type = "session_lifecycle_ack",
                    eventId = result.event.event.eventId.value,
                    state = result.projection.state.name.lowercase(),
                ),
                result.projection.sessionId.value,
                result.isNew,
            )
            is SessionLifecycleResult.Rejected -> RealtimeSessionLifecycleOutcome.Rejected("lifecycle_command_rejected")
        }
    }
}

sealed interface RealtimeSessionLifecycleOutcome {
    data class Acknowledged(
        val acknowledgement: RealtimeSessionLifecycleAcknowledgement,
        val sessionId: String,
        val isNew: Boolean,
    ) : RealtimeSessionLifecycleOutcome

    data class Rejected(val code: String) : RealtimeSessionLifecycleOutcome
}

@Serializable
data class RealtimeResyncRequest(val type: String, val cursor: String?)

@Serializable
data class RealtimeResyncEvent(
    val id: String,
    val ownerPeerId: String,
    val journalSequence: Long,
    val command: RealtimeCommandRequest,
)

@Serializable
data class RealtimeResyncResponse(
    val type: String,
    val cursor: String?,
    val events: List<RealtimeResyncEvent>,
)

@Serializable
data class RealtimeResyncRejected(val type: String, val code: String)

class RealtimeCommands(
    private val maximumReceipts: Int = 1_024,
    private val journal: JdbcPeerJournal? = null,
) {
    init {
        require(maximumReceipts > 0)
    }

    private val acknowledgementsByEventId = mutableMapOf<String, Pair<RealtimeCommandRequest, RealtimeCommandAcknowledgement>>()

    fun accept(command: RealtimeCommandRequest): RealtimeCommandOutcome = synchronized(this) {
        if (
            command.type != "command" ||
            command.eventId.isBlank() ||
            command.sequence < 1 ||
            command.sessionId.isBlank() ||
            (command.payload["type"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.isNotBlank() != true ||
            runCatching { Instant.parse(command.clientTimestamp) }.isFailure
        ) {
            return RealtimeCommandOutcome.Rejected("invalid_command")
        }

        val existing = acknowledgementsByEventId[command.eventId]
        if (existing != null) {
            return if (existing.first == command) {
                RealtimeCommandOutcome.Acknowledged(existing.second)
            } else {
                RealtimeCommandOutcome.Rejected("event_id_conflict")
            }
        }
        if (journal != null) {
            val acknowledgement = RealtimeCommandAcknowledgement("command_ack", command.eventId)
            return try {
                journal.append(command.eventId, Json.encodeToString(command))
                RealtimeCommandOutcome.Acknowledged(acknowledgement)
            } catch (_: IllegalArgumentException) {
                RealtimeCommandOutcome.Rejected("event_id_conflict")
            } catch (_: Exception) {
                RealtimeCommandOutcome.Rejected("journal_unavailable")
            }
        }
        if (acknowledgementsByEventId.size >= maximumReceipts) {
            return RealtimeCommandOutcome.Rejected("command_receipt_limit_reached")
        }

        val acknowledgement = RealtimeCommandAcknowledgement("command_ack", command.eventId)
        acknowledgementsByEventId[command.eventId] = command to acknowledgement
        RealtimeCommandOutcome.Acknowledged(acknowledgement)
    }

    fun resync(cursor: String?): RealtimeResyncOutcome = synchronized(this) {
        val configuredJournal = journal ?: return RealtimeResyncOutcome.Rejected("resync_unavailable")
        try {
            val events = configuredJournal.events
            val cursorIndex = when (cursor) {
                null -> -1
                else -> events.indexOfFirst { it.id == cursor }
            }
            if (cursorIndex == -1 && cursor != null) {
                return RealtimeResyncOutcome.Rejected("invalid_resync_cursor")
            }
            val resyncedEvents = events.drop(cursorIndex + 1).map { event ->
                RealtimeResyncEvent(
                    id = event.id,
                    ownerPeerId = event.ownerPeerId,
                    journalSequence = event.sequence,
                    command = Json.decodeFromString(event.payload),
                )
            }
            RealtimeResyncOutcome.Resynced(
                RealtimeResyncResponse(
                    type = "resync_response",
                    cursor = resyncedEvents.lastOrNull()?.id ?: cursor,
                    events = resyncedEvents,
                ),
            )
        } catch (_: Exception) {
            RealtimeResyncOutcome.Rejected("journal_unavailable")
        }
    }
}

sealed interface RealtimeCommandOutcome {
    data class Acknowledged(val acknowledgement: RealtimeCommandAcknowledgement) : RealtimeCommandOutcome

    data class Rejected(val code: String) : RealtimeCommandOutcome
}

sealed interface RealtimeResyncOutcome {
    data class Resynced(val response: RealtimeResyncResponse) : RealtimeResyncOutcome

    data class Rejected(val code: String) : RealtimeResyncOutcome
}

/** Retains pending judge devices and local operator approval decisions. */
class PairingRequests {
    private val pendingByDeviceId = mutableMapOf<String, PendingPairingRequest>()
    private val acceptedByRequestId = mutableMapOf<String, AcceptedPairingRequest>()
    private val rejectedByRequestId = mutableMapOf<String, PairingStatus>()
    private val revokedByRequestId = mutableMapOf<String, RevokedPairingRequest>()
    private val connectionsByCredential = mutableMapOf<String, Int>()

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

    fun reject(requestId: String): PairingRejection = synchronized(this) {
        rejectedByRequestId[requestId]?.let {
            return PairingRejection.Rejected(it, created = false)
        }
        val pendingEntry = pendingByDeviceId.entries.firstOrNull { it.value.requestId == requestId }
            ?: return PairingRejection.UnknownRequest
        pendingByDeviceId.remove(pendingEntry.key)
        val status = PairingStatus(
            state = PairingStatusState.REJECTED,
            deviceId = pendingEntry.value.deviceId,
            code = PairingStatusCode.OPERATOR_REJECTED,
        )
        rejectedByRequestId[requestId] = status
        PairingRejection.Rejected(status, created = true)
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
        connectionsByCredential.remove(accepted.reconnectCredential)
        PairingRevocation.Revoked(revoked, created = true)
    }

    fun isReconnectCredentialActive(reconnectCredential: String): Boolean = synchronized(this) {
        acceptedByRequestId.values.any { accepted ->
            accepted.reconnectCredential == reconnectCredential && accepted.requestId !in revokedByRequestId
        }
    }

    fun connected(reconnectCredential: String) = synchronized(this) {
        if (isReconnectCredentialActive(reconnectCredential)) {
            connectionsByCredential.merge(reconnectCredential, 1, Int::plus)
        }
    }

    fun disconnected(reconnectCredential: String) = synchronized(this) {
        val connections = connectionsByCredential[reconnectCredential] ?: return@synchronized
        if (connections == 1) {
            connectionsByCredential.remove(reconnectCredential)
        } else {
            connectionsByCredential[reconnectCredential] = connections - 1
        }
    }

    fun operatorDevices(): List<OperatorDeviceConnection> = synchronized(this) {
        acceptedByRequestId.values
            .asSequence()
            .filter { it.requestId !in revokedByRequestId }
            .map {
                OperatorDeviceConnection(
                    deviceId = it.deviceId,
                    platform = it.platform,
                    connectionState = if (connectionsByCredential[it.reconnectCredential] != null) {
                        DeviceConnectionState.CONNECTED
                    } else {
                        DeviceConnectionState.DISCONNECTED
                    },
                )
            }
            .sortedBy(OperatorDeviceConnection::deviceId)
            .toList()
    }

    fun status(requestId: String): PairingStatus? = synchronized(this) {
        pendingByDeviceId.values.firstOrNull { it.requestId == requestId }?.let {
            return PairingStatus(state = PairingStatusState.PENDING, deviceId = it.deviceId)
        }
        acceptedByRequestId[requestId]?.let {
            return PairingStatus(state = PairingStatusState.ACCEPTED, deviceId = it.deviceId)
        }
        rejectedByRequestId[requestId]
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
    realtimeCommands: RealtimeCommands = RealtimeCommands(),
    lifecycleCommands: RealtimeSessionLifecycleCommands? = null,
    kerugiScoreCommands: RealtimeKerugiScoreCommands? = null,
    kerugiOperatorActionCommands: RealtimeKerugiOperatorActionCommands? = null,
    lifecycleStatePublisher: RealtimeSessionStatePublisher = RealtimeSessionStatePublisher(),
    kerugiScorePublisher: RealtimeKerugiScorePublisher = RealtimeKerugiScorePublisher(),
    heartbeatTimeout: Duration = Duration.ofSeconds(30),
) {
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            explicitNulls = false
        })
    }
    install(WebSockets)

    routing {
        get("/") {
            call.respondText("JudgeServer OK")
        }
        get("/v1/health") {
            call.respond(HealthStatus())
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
        get("/v1/pairing-status/{requestId}") {
            val requestId = requireNotNull(call.parameters["requestId"])
            val status = pairingRequests.status(requestId)
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
                send(Frame.Text(Json.encodeToString(RealtimeHandshakeRejected("handshake_rejected", rejectionCode))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, rejectionCode))
                return@webSocket
            }
            val reconnectCredential = requireNotNull(handshake).reconnectCredential
            pairingRequests.connected(reconnectCredential)
            lifecycleStatePublisher.subscribe(this)
            kerugiScorePublisher.subscribe(this)
            val heartbeatTracker = RealtimeHeartbeatTracker(heartbeatTimeout)
            heartbeatTracker.connected(reconnectCredential)
            send(Frame.Text(Json.encodeToString(RealtimeHandshakeAccepted("handshake_accepted"))))
            fun scheduleHeartbeatTimeout() = launch {
                delay(heartbeatTimeout.toMillis())
                if (reconnectCredential in heartbeatTracker.expireInactive()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "heartbeat_timeout"))
                }
            }
            var timeoutJob = scheduleHeartbeatTimeout()
            try {
                while (true) {
                    val frame = incoming.receiveCatching().getOrNull() ?: break
                    val commandText = when (frame) {
                        is Frame.Text -> frame.readText()
                        is Frame.Close -> break
                        else -> continue
                    }
                    if (!pairingRequests.isReconnectCredentialActive(reconnectCredential)) {
                        send(
                            Frame.Text(
                                Json.encodeToString(
                                    RealtimeCommandRejected("command_rejected", "invalid_reconnect_credential"),
                                ),
                            ),
                        )
                        continue
                    }
                    if (commandText.length > 4_096) {
                        send(Frame.Text(Json.encodeToString(RealtimeCommandRejected("command_rejected", "command_too_large"))))
                        continue
                    }
                    val serverReceiveTimestamp = Instant.now().toString()
                    val messageType = runCatching {
                        ((Json.parseToJsonElement(commandText) as? JsonObject)?.get("type") as? JsonPrimitive)
                            ?.takeIf(JsonPrimitive::isString)
                            ?.content
                    }.getOrNull()
                    val clockSync = runCatching { Json.decodeFromString<ClockSyncRequest>(commandText) }.getOrNull()
                    if (messageType == "clock_sync") {
                        if (clockSync == null || runCatching { Instant.parse(clockSync.clientSendTimestamp) }.isFailure) {
                            send(Frame.Text(Json.encodeToString(ClockSyncRejected("clock_sync_rejected", "invalid_clock_sync_timestamp"))))
                        } else {
                            send(
                                Frame.Text(
                                    Json.encodeToString(
                                        ClockSyncResponse(
                                            type = "clock_sync_response",
                                            clientSendTimestamp = clockSync.clientSendTimestamp,
                                            serverReceiveTimestamp = serverReceiveTimestamp,
                                            serverSendTimestamp = Instant.now().toString(),
                                        ),
                                    ),
                                ),
                            )
                        }
                        continue
                    }
                    if (messageType == "heartbeat") {
                        val heartbeat = runCatching { Json.decodeFromString<HeartbeatRequest>(commandText) }.getOrNull()
                        if (heartbeat == null) {
                            send(Frame.Text(Json.encodeToString(HeartbeatRejected("heartbeat_rejected", "invalid_heartbeat"))))
                        } else {
                            heartbeatTracker.heartbeat(reconnectCredential)
                            timeoutJob.cancel()
                            timeoutJob = scheduleHeartbeatTimeout()
                            send(Frame.Text(Json.encodeToString(HeartbeatAcknowledgement("heartbeat_ack"))))
                        }
                        continue
                    }
                    if (messageType == "resync_request") {
                        val request = runCatching { Json.decodeFromString<RealtimeResyncRequest>(commandText) }.getOrNull()
                        val outcome = request?.let { realtimeCommands.resync(it.cursor) }
                            ?: RealtimeResyncOutcome.Rejected("invalid_resync_cursor")
                        when (outcome) {
                            is RealtimeResyncOutcome.Resynced -> send(Frame.Text(Json.encodeToString(outcome.response)))
                            is RealtimeResyncOutcome.Rejected -> {
                                send(Frame.Text(Json.encodeToString(RealtimeResyncRejected("resync_rejected", outcome.code))))
                            }
                        }
                        continue
                    }
                    if (messageType == "session_lifecycle_command") {
                        val request = runCatching {
                            Json.decodeFromString<RealtimeSessionLifecycleCommandRequest>(commandText)
                        }.getOrNull()
                        val outcome = request?.let { lifecycleCommands?.accept(it) }
                            ?: RealtimeSessionLifecycleOutcome.Rejected("lifecycle_unavailable")
                        when (outcome) {
                            is RealtimeSessionLifecycleOutcome.Acknowledged -> {
                                send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                                if (outcome.isNew) {
                                    lifecycleStatePublisher.publish(
                                        RealtimeSessionStateUpdated(
                                            type = "session_state_updated",
                                            sessionId = outcome.sessionId,
                                            state = outcome.acknowledgement.state,
                                        ),
                                    )
                                }
                            }
                            is RealtimeSessionLifecycleOutcome.Rejected -> {
                                send(
                                    Frame.Text(
                                        Json.encodeToString(
                                            RealtimeSessionLifecycleRejected(
                                                "session_lifecycle_rejected",
                                                outcome.code,
                                            ),
                                        ),
                                    ),
                                )
                            }
                        }
                        continue
                    }
                    if (messageType == "kerugi_score_command") {
                        val request = runCatching {
                            Json.decodeFromString<RealtimeKerugiScoreCommandRequest>(commandText)
                        }.getOrNull()
                        val outcome = request?.let { kerugiScoreCommands?.accept(it) }
                            ?: RealtimeKerugiScoreOutcome.Rejected("kerugi_score_unavailable")
                        when (outcome) {
                            is RealtimeKerugiScoreOutcome.Acknowledged -> {
                                send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                                if (outcome.isNew) {
                                    kerugiScorePublisher.publish(
                                        RealtimeKerugiScoreUpdated(
                                            type = "kerugi_score_updated",
                                            sessionId = outcome.sessionId,
                                            blueScore = outcome.acknowledgement.blueScore,
                                            redScore = outcome.acknowledgement.redScore,
                                        ),
                                    )
                                }
                            }
                            is RealtimeKerugiScoreOutcome.Rejected -> {
                                send(
                                    Frame.Text(
                                        Json.encodeToString(
                                            RealtimeKerugiScoreRejected("kerugi_score_rejected", outcome.code),
                                        ),
                                    ),
                                )
                            }
                        }
                        continue
                    }
                    if (messageType == "kerugi_operator_action_command") {
                        val request = runCatching {
                            Json.decodeFromString<RealtimeKerugiOperatorActionCommandRequest>(commandText)
                        }.getOrNull()
                        val outcome = request?.let { kerugiOperatorActionCommands?.accept(it) }
                            ?: RealtimeKerugiScoreOutcome.Rejected("kerugi_operator_action_unavailable")
                        when (outcome) {
                            is RealtimeKerugiScoreOutcome.Acknowledged -> {
                                send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                                if (outcome.isNew) {
                                    kerugiScorePublisher.publish(
                                        RealtimeKerugiScoreUpdated(
                                            type = "kerugi_score_updated",
                                            sessionId = outcome.sessionId,
                                            blueScore = outcome.acknowledgement.blueScore,
                                            redScore = outcome.acknowledgement.redScore,
                                        ),
                                    )
                                }
                            }
                            is RealtimeKerugiScoreOutcome.Rejected -> {
                                send(
                                    Frame.Text(
                                        Json.encodeToString(
                                            RealtimeKerugiScoreRejected("kerugi_operator_action_rejected", outcome.code),
                                        ),
                                    ),
                                )
                            }
                        }
                        continue
                    }
                    val command = runCatching { Json.decodeFromString<RealtimeCommandRequest>(commandText) }.getOrNull()
                    val outcome = command?.let(realtimeCommands::accept) ?: RealtimeCommandOutcome.Rejected("invalid_command")
                    when (outcome) {
                        is RealtimeCommandOutcome.Acknowledged -> send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                        is RealtimeCommandOutcome.Rejected -> {
                            send(Frame.Text(Json.encodeToString(RealtimeCommandRejected("command_rejected", outcome.code))))
                        }
                    }
                }
            } finally {
                timeoutJob.cancel()
                heartbeatTracker.disconnected(reconnectCredential)
                pairingRequests.disconnected(reconnectCredential)
                lifecycleStatePublisher.unsubscribe(this)
                kerugiScorePublisher.unsubscribe(this)
            }
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
