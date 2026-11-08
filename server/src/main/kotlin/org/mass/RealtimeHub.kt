package org.mass

import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mass.bout.BoutChange
import org.mass.bout.BoutCommand
import org.mass.bout.BoutEventJournal
import org.mass.bout.BoutOrigin
import org.mass.bout.BoutOutcome
import org.mass.bout.JudgeContext
import org.mass.bout.KerugiBout
import org.mass.bout.KerugiBoutService
import org.mass.domain.BracketId
import org.mass.domain.CompetitionId
import org.mass.domain.CourtId
import org.mass.domain.EventId
import org.mass.domain.JudgeId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiScoringArea
import org.mass.domain.PeerId
import org.mass.domain.SessionId
import java.time.Instant

/** Server messages always carry every field; a nullable one is sent as `null` (ADR-009 §1.3). */
val realtimeJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class RealtimeCommandAcknowledgement(val type: String, val eventId: String)

/** [eventId] echoes the rejected command's event ID whenever it can be read, so the client can settle that outbox entry. */
@Serializable
data class RealtimeCommandRejected(val type: String, val code: String, val eventId: String?)

@Serializable
data class KerugiScoreCommandMessage(
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
    val competitor: KerugiCompetitor,
    val area: KerugiScoringArea,
)

@Serializable
data class JudgeAttentionCommandMessage(
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
)

@Serializable
data class ResyncRequestMessage(val type: String, val requestId: String, val cursor: Long?)

@Serializable
data class ResyncRejectedMessage(val type: String, val requestId: String?, val code: String)

@Serializable
data class AthleteView(val fullName: String, val city: String)

@Serializable
data class SideView(val entryId: String, val label: String, val athletes: List<AthleteView>)

@Serializable
data class SettingsView(val judgeCount: Int, val quorum: Int, val coincidenceWindowMillis: Long)

@Serializable
data class SeatView(val number: Int, val judgeId: String, val deviceId: String)

@Serializable
data class SidesCount(val blue: Int, val red: Int)

@Serializable
data class WarningView(val code: String, val competitor: KerugiCompetitor, val gamjeomCount: Int)

@Serializable
data class ScoreView(
    val blue: Int,
    val red: Int,
    val gamjeom: SidesCount,
    val warnings: List<WarningView>,
    val disqualified: KerugiCompetitor?,
)

@Serializable
data class ResultView(val winner: KerugiCompetitor, val reason: String)

/** The bout clock of ADR-010 §5: [remainingMillis] is the time left at [runningSince] while running, else exact. */
@Serializable
data class TimerView(
    val period: String,
    val round: Int,
    val goldenRound: Boolean,
    val durationMillis: Long,
    val remainingMillis: Long,
    val running: Boolean,
    val runningSince: String?,
)

@Serializable
data class SessionView(
    val sessionId: String,
    val discipline: String,
    val competitionId: String,
    val peerId: String,
    val courtId: String,
    val courtNumber: Int,
    val bracketId: String,
    val categoryTitle: String,
    val boutNumber: Int,
    val state: String,
    val blue: SideView,
    val red: SideView,
    val settings: SettingsView,
    val seat: SeatView?,
    val score: ScoreView,
    val result: ResultView?,
    val timer: TimerView,
)

@Serializable
data class SessionSnapshotMessage(val type: String, val cursor: Long, val session: SessionView?)

@Serializable
data class ResyncResponseMessage(val type: String, val requestId: String, val cursor: Long, val session: SessionView?)

@Serializable
data class SessionStateUpdatedMessage(
    val type: String,
    val sessionId: String,
    val cursor: Long,
    val state: String,
    val result: ResultView?,
    /** The timer after the transition (ADR-010 §5), so one event causes one push. */
    val timer: TimerView,
)

@Serializable
data class KerugiTimerUpdatedMessage(val type: String, val sessionId: String, val cursor: Long, val timer: TimerView)

@Serializable
data class KerugiScoreUpdatedMessage(val type: String, val sessionId: String, val cursor: Long, val score: ScoreView)

/** One authenticated socket: frames are queued in order and written by the socket's own writer. */
class RealtimeConnection(val deviceId: String, private val outbound: SendChannel<String>) {
    fun send(text: String) {
        outbound.trySend(text)
    }
}

/**
 * The single realtime dispatcher of ADR-009 §11. A judge device may score, signal attention and resync; operator commands
 * come from the desktop process. Every command is applied by [bouts] before its reply, and pushes go only to sockets that
 * resynced, in journal order, after the reply that caused them.
 */
class RealtimeHub(val bouts: KerugiBoutService) {
    private val lock = Any()
    private val subscribers = linkedSetOf<RealtimeConnection>()

    /** Handles a device message other than the transport messages (`clock_sync`, `heartbeat`). */
    fun receive(connection: RealtimeConnection, text: String, type: String?, eventId: String?) = synchronized(lock) {
        when (type) {
            "kerugi_score_command" -> judgeCommand(connection, eventId) {
                val message = Json.decodeFromString<KerugiScoreCommandMessage>(text)
                require(message.type == type)
                BoutCommand.SubmitScore(
                    EventId(message.eventId), message.sequence.also { require(it >= 0) }, Instant.parse(message.clientTimestamp),
                    context(message.competitionId, message.peerId, message.courtId, message.bracketId, message.sessionId, message.judgeId, message.deviceId),
                    message.competitor, message.area,
                )
            }
            "judge_attention_command" -> judgeCommand(connection, eventId) {
                val message = Json.decodeFromString<JudgeAttentionCommandMessage>(text)
                require(message.type == type)
                BoutCommand.SignalAttention(
                    EventId(message.eventId), message.sequence.also { require(it >= 0) }, Instant.parse(message.clientTimestamp),
                    context(message.competitionId, message.peerId, message.courtId, message.bracketId, message.sessionId, message.judgeId, message.deviceId),
                )
            }
            "resync_request" -> resync(connection, text)
            in OPERATOR_TYPES -> reject(connection, "command_not_permitted", eventId)
            else -> reject(connection, "unsupported_command", eventId)
        }
    }

    /** An operator command from the desktop, published to the subscribed devices like a judge command. */
    fun operator(author: String, command: BoutCommand.FromOperator): BoutOutcome = synchronized(lock) {
        bouts.dispatch(BoutOrigin.Operator(author), command).also(::publish)
    }

    /** The server timer of ADR-010 §2: a period that reached its deadline ends and is published like an operator command. */
    fun tick() = synchronized(lock) {
        bouts.tick()?.let(::publish)
    }

    fun leave(connection: RealtimeConnection) = synchronized(lock) {
        subscribers -= connection
    }

    /**
     * The snapshot body a device receives (ADR-009 §3); `seat` is the device's own seat. A null [deviceId] is the read-only
     * view of the spectator window (ADR-010 §8), the same as a connected device without a seat.
     */
    fun session(deviceId: String?): SessionView? = bouts.current()?.let { view(it, deviceId) }

    private inline fun judgeCommand(connection: RealtimeConnection, eventId: String?, decode: () -> BoutCommand.FromJudge) {
        val command = try {
            decode()
        } catch (_: Exception) {
            return reject(connection, "invalid_command", eventId)
        }
        when (val outcome = bouts.dispatch(BoutOrigin.Judge(connection.deviceId), command)) {
            is BoutOutcome.Applied -> {
                ServerLog.commandAcknowledged(connection.deviceId, outcome.eventId.value)
                connection.send(realtimeJson.encodeToString(RealtimeCommandAcknowledgement("command_ack", outcome.eventId.value)))
                publish(outcome)
            }
            is BoutOutcome.Rejected -> reject(connection, outcome.code, eventId)
        }
    }

    private fun resync(connection: RealtimeConnection, text: String) {
        val request = runCatching { Json.decodeFromString<ResyncRequestMessage>(text) }.getOrNull()
            ?.takeIf { it.type == "resync_request" && runCatching { EventId(it.requestId) }.isSuccess }
        if (request == null) {
            val requestId = runCatching {
                (Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("requestId")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.takeIf { it.isString }?.content
            }.getOrNull()
            connection.send(realtimeJson.encodeToString(ResyncRejectedMessage("resync_rejected", requestId, "invalid_resync_request")))
            return
        }
        connection.send(
            realtimeJson.encodeToString(
                ResyncResponseMessage("resync_response", request.requestId, bouts.cursor, session(connection.deviceId)),
            ),
        )
        subscribers += connection
    }

    private fun reject(connection: RealtimeConnection, code: String, eventId: String?) {
        ServerLog.commandRejected(connection.deviceId, code)
        connection.send(realtimeJson.encodeToString(RealtimeCommandRejected("command_rejected", code, eventId)))
    }

    private fun publish(outcome: BoutOutcome) {
        if (outcome !is BoutOutcome.Applied || !outcome.isNew) return
        val bout = bouts.bout(outcome.sessionId) ?: return
        if (BoutChange.NEW_SESSION in outcome.changes) {
            subscribers.forEach { connection ->
                connection.send(
                    realtimeJson.encodeToString(SessionSnapshotMessage("session_snapshot", bouts.cursor, view(bout, connection.deviceId))),
                )
            }
            return
        }
        if (BoutChange.STATE in outcome.changes) {
            val message = realtimeJson.encodeToString(
                SessionStateUpdatedMessage(
                    "session_state_updated", bout.sessionId.value, outcome.sequence, bout.state.name.lowercase(),
                    bout.result?.let { ResultView(it.winner, it.reason) },
                    timerView(bout),
                ),
            )
            subscribers.forEach { it.send(message) }
        } else if (BoutChange.TIMER in outcome.changes) {
            val message = realtimeJson.encodeToString(
                KerugiTimerUpdatedMessage("kerugi_timer_updated", bout.sessionId.value, outcome.sequence, timerView(bout)),
            )
            subscribers.forEach { it.send(message) }
        }
        if (BoutChange.SCORE in outcome.changes) {
            val message = realtimeJson.encodeToString(
                KerugiScoreUpdatedMessage("kerugi_score_updated", bout.sessionId.value, outcome.sequence, scoreView(bout)),
            )
            subscribers.forEach { it.send(message) }
        }
    }

    private fun view(bout: KerugiBout, deviceId: String?): SessionView {
        val started = bout.started
        return SessionView(
            sessionId = bout.sessionId.value,
            discipline = "kerugi",
            competitionId = bout.competitionId.value,
            peerId = bout.peerId.value,
            courtId = bout.courtId.value,
            courtNumber = started.courtNumber,
            bracketId = bout.bracketId.value,
            categoryTitle = started.categoryTitle,
            boutNumber = started.boutNumber,
            state = bout.state.name.lowercase(),
            blue = SideView(started.blue.entryId, started.blue.label, started.blue.athletes.map { AthleteView(it.fullName, it.city) }),
            red = SideView(started.red.entryId, started.red.label, started.red.athletes.map { AthleteView(it.fullName, it.city) }),
            settings = SettingsView(started.seats.size, started.quorum, started.coincidenceWindowMillis),
            seat = deviceId?.let(bout::seatOf)?.let { SeatView(it.number, it.judgeId, it.deviceId) },
            score = scoreView(bout),
            result = bout.result?.let { ResultView(it.winner, it.reason) },
            timer = timerView(bout),
        )
    }

    private fun timerView(bout: KerugiBout): TimerView {
        val timer = bout.clock.timer()
        return TimerView(
            period = timer.period.name.lowercase(),
            round = timer.round,
            goldenRound = timer.golden,
            durationMillis = timer.durationMillis,
            remainingMillis = timer.remainingMillis,
            running = timer.runningSince != null,
            runningSince = timer.runningSince?.toString(),
        )
    }

    private fun scoreView(bout: KerugiBout): ScoreView {
        val score = bout.score()
        return ScoreView(
            blue = score.blue,
            red = score.red,
            gamjeom = SidesCount(score.gamjeomBlue, score.gamjeomRed),
            warnings = score.warnings.map { WarningView("disqualification_pending", it.competitor, it.gamjeomCount) },
            disqualified = score.disqualified,
        )
    }

    private fun context(
        competitionId: String,
        peerId: String,
        courtId: String,
        bracketId: String,
        sessionId: String,
        judgeId: String,
        deviceId: String,
    ) = JudgeContext(
        CompetitionId(competitionId), PeerId(peerId), CourtId(courtId), BracketId(bracketId), SessionId(sessionId),
        JudgeId(judgeId), deviceId,
    )

    companion object {
        /** Operator commands of the earlier device protocol; a judge device can no longer send them (ADR-009 §12). */
        val OPERATOR_TYPES = setOf(
            "session_lifecycle_command",
            "kerugi_operator_action_command",
            "kerugi_score_correction_command",
            "kerugi_disqualification_command",
            "kerugi_timer_command",
            "kerugi_result_command",
        )

        /** A court with an in-memory journal, for tests and a module started without persistence. */
        fun inMemory(peerId: PeerId = PeerId("00000000-0000-4000-8000-000000000001")) =
            RealtimeHub(KerugiBoutService(BoutEventJournal.InMemory(), peerId))
    }
}
