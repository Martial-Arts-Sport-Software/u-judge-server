package org.mass

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.receive
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.mass.domain.KERUGI_SCORE_CORRECTION_EVENT
import org.mass.domain.KERUGI_DISQUALIFICATION_EVENT
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiDisqualification
import org.mass.domain.KerugiDisqualificationPayload
import org.mass.domain.KerugiDisqualificationWarning
import org.mass.domain.KerugiOperatorActionPayload
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.KerugiScoreCandidatePayload
import org.mass.domain.KerugiScoreCorrectionPayload
import org.mass.domain.KerugiScoreEventJournal
import org.mass.domain.KerugiScoreResult
import org.mass.domain.KerugiScoringArea
import org.mass.domain.KerugiResultEventJournal
import org.mass.domain.KerugiResultJournal
import org.mass.domain.KerugiResultJournalResult
import org.mass.domain.KerugiResultPayload
import org.mass.domain.KerugiTimerEventJournal
import org.mass.domain.KerugiTimerResult
import org.mass.domain.KerugiVictoryReason
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import org.mass.domain.SessionLifecycleEventJournal
import org.mass.domain.SessionLifecycleResult
import java.time.Duration
import java.time.Instant
import java.security.SecureRandom
import java.security.MessageDigest
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
        fun local(peerId: String = "peer-local") = ServerMetadata(
            protocolVersion = "1.0",
            capabilities = mapOf("metadata" to true),
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
/** [eventId] echoes the rejected command's event ID whenever it can be read, so the client can settle that outbox entry. */
data class RealtimeCommandRejected(val type: String, val code: String, val eventId: String? = null)

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
data class RealtimeKerugiScoreCorrectionCommandRequest(
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
    val targetEventId: String,
)

@Serializable
data class RealtimeKerugiDisqualificationCommandRequest(
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
    val disqualificationWarnings: List<KerugiDisqualificationWarning>,
    val disqualification: KerugiDisqualification? = null,
)

@Serializable
data class RealtimeKerugiTimerCommandRequest(
    val type: String,
    val eventId: String,
    val sequence: Long,
    val clientTimestamp: String,
    val competitionId: String,
    val peerId: String,
    val courtId: String,
    val bracketId: String,
    val sessionId: String,
    val judgeId: String,
    val deviceId: String,
    val source: String,
    val author: String,
    val action: String,
)

@Serializable
data class RealtimeKerugiTimerAcknowledgement(val type: String, val eventId: String, val state: String)

@Serializable
data class RealtimeKerugiTimerRejected(val type: String, val code: String)

@Serializable
data class RealtimeKerugiTimerUpdated(val type: String, val sessionId: String, val state: String)

@Serializable
data class RealtimeKerugiResultCommandRequest(
    val type: String,
    val eventId: String,
    val sequence: Long,
    val clientTimestamp: String,
    val competitionId: String,
    val peerId: String,
    val courtId: String,
    val bracketId: String,
    val sessionId: String,
    val judgeId: String,
    val deviceId: String,
    val source: String,
    val author: String,
    val winner: String,
    val reason: String,
)

@Serializable
data class RealtimeKerugiResultAcknowledgement(val type: String, val eventId: String, val winner: String, val reason: String)

@Serializable
data class RealtimeKerugiResultRejected(val type: String, val code: String)

@Serializable
data class RealtimeKerugiResultUpdated(val type: String, val sessionId: String, val winner: String, val reason: String)

class RealtimeKerugiTimerCommands(private val journal: KerugiTimerEventJournal) {
    fun accept(request: RealtimeKerugiTimerCommandRequest): RealtimeKerugiTimerOutcome {
        val command = try {
            require(request.type == "kerugi_timer_command")
            require(request.sequence > 0)
            Instant.parse(request.clientTimestamp)
            DomainCommand(
                CompetitionId(request.competitionId), PeerId(request.peerId), CourtId(request.courtId), BracketId(request.bracketId),
                SessionId(request.sessionId), JudgeId(request.judgeId), DeviceId(request.deviceId), EventSource(request.source),
                request.author, timerEventType(request.action), "{}",
            )
        } catch (_: IllegalArgumentException) {
            return RealtimeKerugiTimerOutcome.Rejected("invalid_kerugi_timer_command")
        }
        val eventId = try { EventId(request.eventId) } catch (_: IllegalArgumentException) {
            return RealtimeKerugiTimerOutcome.Rejected("invalid_kerugi_timer_command")
        }
        return try {
            when (val result = journal.apply(command, eventId)) {
                is KerugiTimerResult.Applied -> RealtimeKerugiTimerOutcome.Acknowledged(
                    RealtimeKerugiTimerAcknowledgement("kerugi_timer_ack", result.event.event.eventId.value, result.projection.state.name.lowercase()),
                    result.projection.sessionId.value, result.isNew,
                )
                is KerugiTimerResult.Rejected -> RealtimeKerugiTimerOutcome.Rejected("kerugi_timer_command_rejected")
            }
        } catch (_: Exception) { RealtimeKerugiTimerOutcome.Rejected("kerugi_timer_unavailable") }
    }
}

private fun timerEventType(action: String) = when (action) {
    "START" -> "kerugi_timer_started"
    "PAUSE" -> "kerugi_timer_paused"
    "RESUME" -> "kerugi_timer_resumed"
    "START_BREAK" -> "kerugi_round_break_started"
    "END_BREAK" -> "kerugi_round_break_ended"
    "STOP" -> "kerugi_timer_stopped"
    else -> throw IllegalArgumentException("Unsupported Kerugi timer action")
}

class RealtimeKerugiResultCommands(private val journal: KerugiResultEventJournal) {
    fun accept(request: RealtimeKerugiResultCommandRequest): RealtimeKerugiResultOutcome {
        val command = try {
            require(request.type == "kerugi_result_command")
            require(request.sequence > 0)
            Instant.parse(request.clientTimestamp)
            val winner = KerugiCompetitor.valueOf(request.winner)
            val reason = KerugiVictoryReason.valueOf(request.reason)
            DomainCommand(
                CompetitionId(request.competitionId), PeerId(request.peerId), CourtId(request.courtId), BracketId(request.bracketId),
                SessionId(request.sessionId), JudgeId(request.judgeId), DeviceId(request.deviceId), EventSource(request.source),
                request.author, KerugiResultJournal.KERUGI_RESULT_EVENT,
                Json.encodeToString(KerugiResultPayload(winner.name, reason.name)),
            )
        } catch (_: IllegalArgumentException) {
            return RealtimeKerugiResultOutcome.Rejected("invalid_kerugi_result_command")
        }
        val eventId = try { EventId(request.eventId) } catch (_: IllegalArgumentException) {
            return RealtimeKerugiResultOutcome.Rejected("invalid_kerugi_result_command")
        }
        return try {
            when (val result = journal.apply(command, eventId)) {
                is KerugiResultJournalResult.Applied -> RealtimeKerugiResultOutcome.Acknowledged(
                    RealtimeKerugiResultAcknowledgement(
                        "kerugi_result_ack", result.event.event.eventId.value,
                        requireNotNull(result.projection.winner).name.lowercase(), requireNotNull(result.projection.reason).name.lowercase(),
                    ),
                    result.projection.sessionId.value, result.isNew,
                )
                is KerugiResultJournalResult.Rejected -> RealtimeKerugiResultOutcome.Rejected("kerugi_result_command_rejected")
            }
        } catch (_: Exception) { RealtimeKerugiResultOutcome.Rejected("kerugi_result_unavailable") }
    }
}

sealed interface RealtimeKerugiResultOutcome {
    data class Acknowledged(val acknowledgement: RealtimeKerugiResultAcknowledgement, val sessionId: String, val isNew: Boolean) : RealtimeKerugiResultOutcome
    data class Rejected(val code: String) : RealtimeKerugiResultOutcome
}

sealed interface RealtimeKerugiTimerOutcome {
    data class Acknowledged(val acknowledgement: RealtimeKerugiTimerAcknowledgement, val sessionId: String, val isNew: Boolean) : RealtimeKerugiTimerOutcome
    data class Rejected(val code: String) : RealtimeKerugiTimerOutcome
}

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
                    result.projection.disqualificationWarnings,
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
                    result.projection.disqualificationWarnings,
                )
                is KerugiScoreResult.Rejected -> RealtimeKerugiScoreOutcome.Rejected("kerugi_operator_action_command_rejected")
            }
        } catch (_: Exception) {
            RealtimeKerugiScoreOutcome.Rejected("kerugi_operator_action_unavailable")
        }
    }
}

class RealtimeKerugiScoreCorrectionCommands(private val journal: KerugiScoreEventJournal) {
    fun accept(request: RealtimeKerugiScoreCorrectionCommandRequest): RealtimeKerugiScoreOutcome {
        val eventId = try {
            require(request.type == "kerugi_score_correction_command")
            val payload = Json.encodeToString(KerugiScoreCorrectionPayload(request.targetEventId))
            val command = DomainCommand(
                CompetitionId(request.competitionId), PeerId(request.peerId), CourtId(request.courtId), BracketId(request.bracketId),
                SessionId(request.sessionId), JudgeId(request.judgeId), DeviceId(request.deviceId), EventSource(request.source),
                request.author, KERUGI_SCORE_CORRECTION_EVENT, payload,
            )
            EventId(request.eventId) to command
        } catch (_: IllegalArgumentException) {
            return RealtimeKerugiScoreOutcome.Rejected("invalid_kerugi_score_correction_command")
        }
        return try {
            when (val result = journal.apply(eventId.second, eventId.first)) {
                is KerugiScoreResult.Applied -> RealtimeKerugiScoreOutcome.Acknowledged(
                    RealtimeKerugiScoreAcknowledgement(
                        "kerugi_score_correction_ack", result.event.event.eventId.value,
                        result.projection.blueScore, result.projection.redScore,
                    ),
                    result.event.event.sessionId.value,
                    result.isNew,
                    result.projection.disqualificationWarnings,
                )
                is KerugiScoreResult.Rejected -> RealtimeKerugiScoreOutcome.Rejected("kerugi_score_correction_command_rejected")
            }
        } catch (_: Exception) {
            RealtimeKerugiScoreOutcome.Rejected("kerugi_score_correction_unavailable")
        }
    }
}

class RealtimeKerugiDisqualificationCommands(private val journal: KerugiScoreEventJournal) {
    fun accept(request: RealtimeKerugiDisqualificationCommandRequest): RealtimeKerugiScoreOutcome {
        val eventId = try {
            require(request.type == "kerugi_disqualification_command")
            val command = DomainCommand(
                CompetitionId(request.competitionId), PeerId(request.peerId), CourtId(request.courtId), BracketId(request.bracketId),
                SessionId(request.sessionId), JudgeId(request.judgeId), DeviceId(request.deviceId), EventSource(request.source),
                request.author, KERUGI_DISQUALIFICATION_EVENT, Json.encodeToString(KerugiDisqualificationPayload(request.competitor)),
            )
            EventId(request.eventId) to command
        } catch (_: IllegalArgumentException) {
            return RealtimeKerugiScoreOutcome.Rejected("invalid_kerugi_disqualification_command")
        }
        return try {
            when (val result = journal.apply(eventId.second, eventId.first)) {
                is KerugiScoreResult.Applied -> RealtimeKerugiScoreOutcome.Acknowledged(
                    RealtimeKerugiScoreAcknowledgement(
                        "kerugi_disqualification_ack", result.event.event.eventId.value,
                        result.projection.blueScore, result.projection.redScore,
                    ),
                    result.event.event.sessionId.value, result.isNew, result.projection.disqualificationWarnings,
                    result.projection.disqualification,
                )
                is KerugiScoreResult.Rejected -> RealtimeKerugiScoreOutcome.Rejected("kerugi_disqualification_command_rejected")
            }
        } catch (_: Exception) {
            RealtimeKerugiScoreOutcome.Rejected("kerugi_disqualification_unavailable")
        }
    }
}

sealed interface RealtimeKerugiScoreOutcome {
    data class Acknowledged(
        val acknowledgement: RealtimeKerugiScoreAcknowledgement,
        val sessionId: String,
        val isNew: Boolean,
        val disqualificationWarnings: List<KerugiDisqualificationWarning>,
        val disqualification: KerugiDisqualification? = null,
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

class RealtimeKerugiTimerPublisher {
    private val subscribers = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())
    fun subscribe(session: WebSocketSession) { subscribers += session }
    fun unsubscribe(session: WebSocketSession) { subscribers -= session }
    suspend fun publish(update: RealtimeKerugiTimerUpdated) {
        synchronized(subscribers) { subscribers.toList() }.forEach { session ->
            runCatching { session.send(Frame.Text(Json.encodeToString(update))) }.onFailure { unsubscribe(session) }
        }
    }
}

class RealtimeKerugiResultPublisher {
    private val subscribers = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())
    fun subscribe(session: WebSocketSession) { subscribers += session }
    fun unsubscribe(session: WebSocketSession) { subscribers -= session }
    suspend fun publish(update: RealtimeKerugiResultUpdated) {
        synchronized(subscribers) { subscribers.toList() }.forEach { session ->
            runCatching { session.send(Frame.Text(Json.encodeToString(update))) }.onFailure { unsubscribe(session) }
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
            if (deliveryProof != null &&
                (existing.deliveryProofHash == null || existing.deliveryProofHash != hashHex(deliveryProof))) {
                return PairingSubmission.Rejected
            }
            return PairingSubmission.Pending(existing.pending(), created = false)
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

        fun pending() = PendingPairingRequest(requestId, deviceId, surname, platform)

        fun accepted(credential: String) = AcceptedPairingRequest(requestId, deviceId, surname, platform, credential)

        fun revoked() = RevokedPairingRequest(requestId, deviceId, surname, platform)

        fun rejectedStatus() = PairingStatus(
            state = PairingStatusState.REJECTED,
            deviceId = deviceId,
            code = PairingStatusCode.OPERATOR_REJECTED,
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
    realtimeCommands: RealtimeCommands = RealtimeCommands(),
    lifecycleCommands: RealtimeSessionLifecycleCommands? = null,
    kerugiScoreCommands: RealtimeKerugiScoreCommands? = null,
    kerugiOperatorActionCommands: RealtimeKerugiOperatorActionCommands? = null,
    kerugiScoreCorrectionCommands: RealtimeKerugiScoreCorrectionCommands? = null,
    kerugiDisqualificationCommands: RealtimeKerugiDisqualificationCommands? = null,
    kerugiTimerCommands: RealtimeKerugiTimerCommands? = null,
    kerugiResultCommands: RealtimeKerugiResultCommands? = null,
    lifecycleStatePublisher: RealtimeSessionStatePublisher = RealtimeSessionStatePublisher(),
    kerugiScorePublisher: RealtimeKerugiScorePublisher = RealtimeKerugiScorePublisher(),
    kerugiTimerPublisher: RealtimeKerugiTimerPublisher = RealtimeKerugiTimerPublisher(),
    kerugiResultPublisher: RealtimeKerugiResultPublisher = RealtimeKerugiResultPublisher(),
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
    install(WebSockets)

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
            lifecycleStatePublisher.subscribe(this)
            kerugiScorePublisher.subscribe(this)
            kerugiTimerPublisher.subscribe(this)
            kerugiResultPublisher.subscribe(this)
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
                    val messageEventId = if (commandText.length > 4_096) null else runCatching {
                        ((Json.parseToJsonElement(commandText) as? JsonObject)?.get("eventId") as? JsonPrimitive)
                            ?.takeIf(JsonPrimitive::isString)
                            ?.content
                    }.getOrNull()
                    if (!pairingRequests.isReconnectCredentialActive(reconnectCredential)) {
                        ServerLog.commandRejected(deviceId, "invalid_reconnect_credential")
                        send(
                            Frame.Text(
                                Json.encodeToString(
                                    RealtimeCommandRejected("command_rejected", "invalid_reconnect_credential", messageEventId),
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
                                            disqualificationWarnings = outcome.disqualificationWarnings,
                                            disqualification = outcome.disqualification,
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
                    if (messageType == "kerugi_timer_command") {
                        val request = runCatching { Json.decodeFromString<RealtimeKerugiTimerCommandRequest>(commandText) }.getOrNull()
                        val outcome = request?.let { kerugiTimerCommands?.accept(it) }
                            ?: RealtimeKerugiTimerOutcome.Rejected("kerugi_timer_unavailable")
                        when (outcome) {
                            is RealtimeKerugiTimerOutcome.Acknowledged -> {
                                send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                                if (outcome.isNew) kerugiTimerPublisher.publish(
                                    RealtimeKerugiTimerUpdated("kerugi_timer_updated", outcome.sessionId, outcome.acknowledgement.state),
                                )
                            }
                            is RealtimeKerugiTimerOutcome.Rejected -> send(
                                Frame.Text(Json.encodeToString(RealtimeKerugiTimerRejected("kerugi_timer_rejected", outcome.code))),
                            )
                        }
                        continue
                    }
                    if (messageType == "kerugi_result_command") {
                        val request = runCatching { Json.decodeFromString<RealtimeKerugiResultCommandRequest>(commandText) }.getOrNull()
                        val outcome = request?.let { kerugiResultCommands?.accept(it) }
                            ?: RealtimeKerugiResultOutcome.Rejected("kerugi_result_unavailable")
                        when (outcome) {
                            is RealtimeKerugiResultOutcome.Acknowledged -> {
                                send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                                if (outcome.isNew) kerugiResultPublisher.publish(
                                    RealtimeKerugiResultUpdated(
                                        "kerugi_result_updated", outcome.sessionId,
                                        outcome.acknowledgement.winner, outcome.acknowledgement.reason,
                                    ),
                                )
                            }
                            is RealtimeKerugiResultOutcome.Rejected -> send(
                                Frame.Text(Json.encodeToString(RealtimeKerugiResultRejected("kerugi_result_rejected", outcome.code))),
                            )
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
                                            disqualificationWarnings = outcome.disqualificationWarnings,
                                            disqualification = outcome.disqualification,
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
                    if (messageType == "kerugi_score_correction_command") {
                        val request = runCatching {
                            Json.decodeFromString<RealtimeKerugiScoreCorrectionCommandRequest>(commandText)
                        }.getOrNull()
                        val outcome = request?.let { kerugiScoreCorrectionCommands?.accept(it) }
                            ?: RealtimeKerugiScoreOutcome.Rejected("kerugi_score_correction_unavailable")
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
                                            disqualificationWarnings = outcome.disqualificationWarnings,
                                        ),
                                    )
                                }
                            }
                            is RealtimeKerugiScoreOutcome.Rejected -> {
                                send(
                                    Frame.Text(
                                        Json.encodeToString(
                                            RealtimeKerugiScoreRejected("kerugi_score_correction_rejected", outcome.code),
                                        ),
                                    ),
                                )
                            }
                        }
                        continue
                    }
                    if (messageType == "kerugi_disqualification_command") {
                        val request = runCatching {
                            Json.decodeFromString<RealtimeKerugiDisqualificationCommandRequest>(commandText)
                        }.getOrNull()
                        val outcome = request?.let { kerugiDisqualificationCommands?.accept(it) }
                            ?: RealtimeKerugiScoreOutcome.Rejected("kerugi_disqualification_unavailable")
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
                                            disqualificationWarnings = outcome.disqualificationWarnings,
                                            disqualification = outcome.disqualification,
                                        ),
                                    )
                                }
                            }
                            is RealtimeKerugiScoreOutcome.Rejected -> send(
                                Frame.Text(
                                    Json.encodeToString(
                                        RealtimeKerugiScoreRejected("kerugi_disqualification_rejected", outcome.code),
                                    ),
                                ),
                            )
                        }
                        continue
                    }
                    val command = runCatching { Json.decodeFromString<RealtimeCommandRequest>(commandText) }.getOrNull()
                    val outcome = command?.let(realtimeCommands::accept) ?: RealtimeCommandOutcome.Rejected("invalid_command")
                    when (outcome) {
                        is RealtimeCommandOutcome.Acknowledged -> {
                            ServerLog.commandAcknowledged(deviceId, outcome.acknowledgement.eventId)
                            send(Frame.Text(Json.encodeToString(outcome.acknowledgement)))
                        }
                        is RealtimeCommandOutcome.Rejected -> {
                            ServerLog.commandRejected(deviceId, outcome.code)
                            send(
                                Frame.Text(
                                    Json.encodeToString(RealtimeCommandRejected("command_rejected", outcome.code, messageEventId)),
                                ),
                            )
                        }
                    }
                }
            } finally {
                timeoutJob.cancel()
                heartbeatTracker.disconnected(reconnectCredential)
                pairingRequests.disconnected(reconnectCredential)
                ServerLog.deviceDisconnected(deviceId)
                lifecycleStatePublisher.unsubscribe(this)
                kerugiScorePublisher.unsubscribe(this)
                kerugiTimerPublisher.unsubscribe(this)
                kerugiResultPublisher.unsubscribe(this)
            }
        }
    }
}

fun main() {
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
