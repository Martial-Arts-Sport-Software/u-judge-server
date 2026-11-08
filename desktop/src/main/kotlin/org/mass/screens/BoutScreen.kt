package org.mass.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mass.DeviceConnectionState
import org.mass.Server
import org.mass.State
import org.mass.bout.BoutCommand
import org.mass.bout.BoutEntryStatus
import org.mass.bout.BoutHistoryEntry
import org.mass.bout.BoutStateAction
import org.mass.bout.ConnectedJudge
import org.mass.bout.CourtBoutOutcome
import org.mass.bout.CourtBouts
import org.mass.draw.CourtItem
import org.mass.draw.CourtPanel
import org.mass.draw.DrawOutcome
import org.mass.draw.ItemRef
import org.mass.ui.court.CourtPanelComponent
import org.mass.bout.KerugiBout
import org.mass.bout.KerugiBoutEvents
import org.mass.domain.EventId
import org.mass.domain.KerugiCompetitor
import org.mass.domain.KerugiOperatorActionType
import org.mass.domain.SessionState
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.dialog.ConfirmDialogComponent
import org.mass.ui.input.TextInputComponent
import org.mass.ui.screen_header.ScreenHeaderComponent
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The operator's Kerugi bout (I2a, ADR-009): «Следующий бой» with the roster of connected judges, quorum and window, then
 * the scoreboard with throws, spins, Gamjeom, state changes, corrections, the reset and the result. Every action is a
 * journaled command of the court dispatcher, written off the UI thread; the screen only renders the projection.
 */
object BoutScreen : Screen {
    private sealed interface Confirmation {
        data class Undo(val entry: BoutHistoryEntry) : Confirmation
        data object Reset : Confirmation
        data object Complete : Confirmation
        data object Cancel : Confirmation
        data class Disqualify(val competitor: KerugiCompetitor) : Confirmation
    }

    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val court = remember(runtimeState) { Server.runtime.courtBouts }
        val boutRevision by (court?.bouts?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val drawRevision by (Server.runtime.competitionDraws?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val pairingChanges by Server.runtime.pairingRequests.changes.collectAsState()
        val current = remember(court, boutRevision) { court?.bouts?.current() }
        val panel = remember(court, boutRevision, drawRevision) { court?.panel() ?: CourtPanel.Empty }
        val connected = remember(court, pairingChanges) { court?.connectedJudges().orEmpty() }
        val registry = remember(pairingChanges) { Server.runtime.pairingRequests.operatorRegistry() }
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var confirmation by remember { mutableStateOf<Confirmation?>(null) }
        var seats by remember { mutableStateOf(listOf<String>()) }
        var quorum by remember { mutableStateOf("2") }
        var window by remember { mutableStateOf("1000") }

        fun act(block: CourtBouts.() -> CourtBoutOutcome) {
            val target = court ?: return
            scope.launch {
                busy = true
                val outcome = withContext(Dispatchers.IO) { runCatching { target.block() } }
                message = outcome.fold(
                    onSuccess = { result ->
                        when (result) {
                            is CourtBoutOutcome.Done -> null
                            is CourtBoutOutcome.Rejected -> Localization.getString("bout_rejected").replace("%s", result.code)
                            is CourtBoutOutcome.Failed -> Localization.getString("bout_failed").replace("%s", result.diagnostic)
                        }
                    },
                    onFailure = { Localization.getString("bout_failed").replace("%s", it.message ?: it::class.simpleName.orEmpty()) },
                )
                busy = false
            }
        }

        fun operator(command: (EventId) -> BoutCommand.FromOperator) = act { command(State.judgeSurname, command(EventId.new())) }

        val active = current?.takeIf { !it.closed }
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(0.34f).fillMaxHeight().clip(PANEL_SHAPE).background(Colors.GRAY.color).padding(20.dp),
                ) {
                    if (active == null) {
                        NextBout(
                            panel = panel,
                            onPick = { ref ->
                                val courts = Server.runtime.competitionCourts
                                if (courts != null) {
                                    scope.launch {
                                        val outcome = withContext(Dispatchers.IO) { courts.selectNextBout(ref) }
                                        message = (outcome as? DrawOutcome.Rejected)?.let {
                                            Localization.getString("bout_rejected").replace("%s", it.reason.name.lowercase())
                                        }
                                    }
                                }
                            },
                            connected = connected,
                            seats = seats,
                            onSeat = { device -> seats = if (device in seats) seats - device else (seats + device).take(3) },
                            quorum = quorum,
                            onQuorum = { quorum = it },
                            window = window,
                            onWindow = { window = it },
                            enabled = !busy && court != null,
                            onStart = {
                                act {
                                    start(seats.filter { it in connected.map(ConnectedJudge::deviceId) }, quorum.toIntOrNull() ?: 0,
                                        window.toLongOrNull() ?: 0, State.judgeSurname)
                                }
                            },
                        )
                    } else {
                        Seats(active, registry.devices.associate { it.deviceId to it.connectionState })
                        ButtonComponent(
                            Localization.getString("bout_cancel"),
                            ButtonStyles.Secondary,
                            onclick = { confirmation = Confirmation.Cancel },
                            enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = Localization.getString("bout_cancel") },
                        )
                    }
                    message?.let { Text(it, color = Color(0xFFFFB4B4), style = MaterialTheme.typography.bodyMedium) }
                }
                Spacer(Modifier.width(25.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(0.66f).fillMaxHeight().clip(PANEL_SHAPE).background(Colors.GRAY.color).padding(20.dp),
                ) {
                    if (current == null) {
                        Text(Localization.getString("bout_none"), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    } else {
                        Scoreboard(
                            bout = current,
                            enabled = !busy && !current.closed,
                            onState = { action -> operator { BoutCommand.ChangeState(it, current.sessionId, action) } },
                            onAction = { type, competitor, points ->
                                operator { BoutCommand.AwardAction(it, current.sessionId, type, competitor, points) }
                            },
                            onDisqualify = { confirmation = Confirmation.Disqualify(it) },
                            onReset = { confirmation = Confirmation.Reset },
                            onComplete = { confirmation = Confirmation.Complete },
                        )
                        History(current, enabled = !busy && !current.closed, onUndo = { confirmation = Confirmation.Undo(it) })
                    }
                }
            }
        }

        val bout = current
        when (val pending = confirmation) {
            null -> Unit
            is Confirmation.Undo -> ConfirmDialogComponent(
                title = Localization.getString("bout_undo_title"),
                text = Localization.getString("bout_undo_text"),
                confirmText = Localization.getString("bout_undo"),
                cancelText = Localization.getString("bout_back"),
                onConfirm = {
                    confirmation = null
                    if (bout != null) operator { BoutCommand.CorrectScore(it, bout.sessionId, pending.entry.eventId) }
                },
                onCancel = { confirmation = null },
            )
            Confirmation.Reset -> ReasonDialog(
                title = Localization.getString("bout_reset_title"),
                text = Localization.getString("bout_reset_text"),
                confirmText = Localization.getString("bout_reset"),
                onConfirm = { reason ->
                    confirmation = null
                    if (bout != null) operator { BoutCommand.ResetBout(it, bout.sessionId, reason) }
                },
                onCancel = { confirmation = null },
            )
            Confirmation.Cancel -> ReasonDialog(
                title = Localization.getString("bout_cancel_title"),
                text = Localization.getString("bout_cancel_text"),
                confirmText = Localization.getString("bout_cancel"),
                onConfirm = { reason ->
                    confirmation = null
                    if (bout != null) operator { BoutCommand.CancelBout(it, bout.sessionId, reason) }
                },
                onCancel = { confirmation = null },
            )
            Confirmation.Complete -> {
                val score = bout?.score()
                val winner = when {
                    score == null -> null
                    score.disqualified != null -> opponent(score.disqualified!!)
                    score.blue > score.red -> KerugiCompetitor.BLUE
                    score.red > score.blue -> KerugiCompetitor.RED
                    else -> null
                }
                ConfirmDialogComponent(
                    title = Localization.getString("bout_complete_title"),
                    text = if (winner == null || score == null) {
                        Localization.getString("bout_complete_tied")
                    } else {
                        Localization.getString("bout_complete_text")
                            .replaceFirst("%d", score.blue.toString())
                            .replaceFirst("%d", score.red.toString())
                            .replaceFirst("%s", sideName(winner))
                    },
                    confirmText = Localization.getString("bout_complete"),
                    cancelText = Localization.getString("bout_back"),
                    onConfirm = {
                        confirmation = null
                        if (bout != null && winner != null) operator { BoutCommand.CompleteBout(it, bout.sessionId) }
                    },
                    onCancel = { confirmation = null },
                )
            }
            is Confirmation.Disqualify -> ConfirmDialogComponent(
                title = Localization.getString("bout_disqualify_title"),
                text = Localization.getString("bout_disqualify_text").replace("%s", sideName(pending.competitor)),
                confirmText = Localization.getString("bout_disqualify"),
                cancelText = Localization.getString("bout_back"),
                onConfirm = {
                    confirmation = null
                    if (bout != null) operator { BoutCommand.ConfirmDisqualification(it, bout.sessionId, pending.competitor) }
                },
                onCancel = { confirmation = null },
            )
        }
    }

    /** The court panel with «Следующий бой» (I4b), the judges in seat order and the settings fixed at «Начать». */
    @Composable
    private fun NextBout(
        panel: CourtPanel,
        onPick: (ItemRef) -> Unit,
        connected: List<ConnectedJudge>,
        seats: List<String>,
        onSeat: (String) -> Unit,
        quorum: String,
        onQuorum: (String) -> Unit,
        window: String,
        onWindow: (String) -> Unit,
        enabled: Boolean,
        onStart: () -> Unit,
    ) {
        val seated = seats.count { device -> connected.any { it.deviceId == device } }
        val startable = enabled && panel.next is CourtItem.Bout && seated in 2..3
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Colors.SECONDARY.color).padding(14.dp)) {
            CourtPanelComponent(panel, onPick, Modifier.fillMaxWidth().height(260.dp), enabled = enabled, onStart = onStart.takeIf { startable })
        }
        if (panel.next == null) Hint(Localization.getString("bout_select_empty"))
        PanelTitle(Localization.getString("bout_judges_title"))
        if (connected.isEmpty()) {
            Hint(Localization.getString("bout_judges_empty"))
        } else {
            Hint(Localization.getString("bout_judges_hint"))
            connected.forEach { judge ->
                val seat = seats.indexOf(judge.deviceId)
                SelectableRow(
                    text = (if (seat >= 0) "${seat + 1}. " else "") + judge.surname,
                    detail = judge.platform,
                    selected = seat >= 0,
                    onClick = { onSeat(judge.deviceId) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInputComponent(Localization.getString("bout_quorum"), quorum, onQuorum, Modifier.weight(1f), labelColor = Color.White)
            TextInputComponent(Localization.getString("bout_window"), window, onWindow, Modifier.weight(1f), labelColor = Color.White)
        }
    }

    @Composable
    private fun Seats(bout: KerugiBout, connection: Map<String, DeviceConnectionState?>) {
        PanelTitle(Localization.getString("bout_seats_title"))
        bout.started.seats.forEach { seat ->
            val online = connection[seat.deviceId] == DeviceConnectionState.CONNECTED
            SelectableRow(
                text = Localization.getString("bout_seat").replaceFirst("%d", seat.number.toString()).replaceFirst("%s", seat.surname),
                detail = Localization.getString(if (online) "bout_seat_connected" else "bout_seat_disconnected"),
                selected = online,
                onClick = {},
            )
        }
        Hint(
            Localization.getString("bout_settings")
                .replaceFirst("%d", bout.started.quorum.toString())
                .replaceFirst("%d", bout.started.seats.size.toString())
                .replaceFirst("%d", bout.started.coincidenceWindowMillis.toString()),
        )
        bout.attention.takeLast(3).reversed().forEach { signal ->
            Text(
                Localization.getString("bout_attention")
                    .replaceFirst("%d", (signal.seat ?: 0).toString())
                    .replaceFirst("%s", TIME.format(signal.receivedAt)),
                color = Color(0xFFFFE08A),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun Scoreboard(
        bout: KerugiBout,
        enabled: Boolean,
        onState: (BoutStateAction) -> Unit,
        onAction: (KerugiOperatorActionType, KerugiCompetitor, Int) -> Unit,
        onDisqualify: (KerugiCompetitor) -> Unit,
        onReset: () -> Unit,
        onComplete: () -> Unit,
    ) {
        val score = bout.score()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Localization.getString("bout_number").replace("%d", bout.started.boutNumber.toString()) + " · " + bout.started.categoryTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(stateLabel(bout.state), color = Colors.SECONDARY.color, style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(KerugiCompetitor.BLUE to bout.started.blue.label, KerugiCompetitor.RED to bout.started.red.label).forEach { (side, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (side == KerugiCompetitor.BLUE) Colors.BLUE.color else Colors.RED.color)
                        .padding(12.dp),
                ) {
                    Text(sideName(side) + " · " + label, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Text(
                        (if (side == KerugiCompetitor.BLUE) score.blue else score.red).toString(),
                        color = Color.White,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = sideName(side) },
                    )
                    Text(
                        Localization.getString("bout_gamjeom_count")
                            .replace("%d", (if (side == KerugiCompetitor.BLUE) score.gamjeomBlue else score.gamjeomRed).toString()),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val scoring = enabled && (bout.state == SessionState.RUNNING || bout.state == SessionState.PAUSED)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..3).forEach { points ->
                            SmallButton(Localization.getString("bout_throw").replace("%d", points.toString()), scoring, Modifier.weight(1f)) {
                                onAction(KerugiOperatorActionType.THROW, side, points)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton(Localization.getString("bout_spin"), scoring, Modifier.weight(1f)) {
                            onAction(KerugiOperatorActionType.SPIN_BONUS, side, 1)
                        }
                        SmallButton(Localization.getString("bout_gamjeom"), scoring, Modifier.weight(1f)) {
                            onAction(KerugiOperatorActionType.GAMJEOM, side, 1)
                        }
                    }
                }
            }
        }
        score.warnings.forEach { warning ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    Localization.getString("bout_disqualification").replace("%s", sideName(warning.competitor)),
                    color = Color(0xFFFFE08A),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                SmallButton(Localization.getString("bout_disqualify"), enabled, Modifier.width(220.dp)) { onDisqualify(warning.competitor) }
            }
        }
        score.disqualified?.let {
            Text(Localization.getString("bout_disqualified").replace("%s", sideName(it)), color = Color(0xFFFFE08A), style = MaterialTheme.typography.bodyMedium)
        }
        bout.result?.let { result ->
            Text(
                Localization.getString("bout_result")
                    .replaceFirst("%s", sideName(result.winner))
                    .replaceFirst("%s", Localization.getString("bout_reason_${result.reason}")),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (bout.state) {
                SessionState.PREPARED -> SmallButton(Localization.getString("bout_begin"), enabled, Modifier.weight(1f)) { onState(BoutStateAction.START) }
                SessionState.RUNNING -> SmallButton(Localization.getString("bout_pause"), enabled, Modifier.weight(1f)) { onState(BoutStateAction.PAUSE) }
                SessionState.PAUSED -> SmallButton(Localization.getString("bout_resume"), enabled, Modifier.weight(1f)) { onState(BoutStateAction.RESUME) }
                else -> Spacer(Modifier.weight(1f))
            }
            val scoring = enabled && (bout.state == SessionState.RUNNING || bout.state == SessionState.PAUSED)
            SmallButton(Localization.getString("bout_reset"), scoring, Modifier.weight(1f), onReset)
            SmallButton(Localization.getString("bout_complete"), scoring, Modifier.weight(1f), onComplete)
        }
    }

    /** Every journaled input with why it did or did not count (`KER-009`); an effective one can be undone (`KER-008`). */
    @Composable
    private fun History(bout: KerugiBout, enabled: Boolean, onUndo: (BoutHistoryEntry) -> Unit) {
        PanelTitle(Localization.getString("bout_history_title"))
        val entries = remember(bout) {
            bout.history().filter { it.type == KerugiBoutEvents.SCORE_CANDIDATE || it.type == KerugiBoutEvents.OPERATOR_ACTION }.reversed()
        }
        if (entries.isEmpty()) {
            Hint(Localization.getString("bout_history_empty"))
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.eventId.value }) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(TIME.format(entry.receivedAt), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entryText(entry) + " · " + statusLabel(entry.status),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.correctable) {
                        SmallButton(Localization.getString("bout_undo"), enabled, Modifier.width(110.dp)) { onUndo(entry) }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun ReasonDialog(title: String, text: String, confirmText: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
        var reason by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Black.copy(alpha = 0.6f)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.width(460.dp).clip(RoundedCornerShape(20.dp)).background(Colors.SECONDARY.color).padding(22.dp),
            ) {
                Text(title, color = Colors.BROWN.color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text, color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
                TextInputComponent(Localization.getString("bout_reason"), reason, { reason = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
                    ButtonComponent(Localization.getString("bout_back"), ButtonStyles.Plain, Modifier.width(120.dp), onclick = onCancel)
                    ButtonComponent(confirmText, modifier = Modifier.width(200.dp), onclick = { onConfirm(reason.trim()) }, enabled = reason.isNotBlank())
                }
            }
        }
    }

    @Composable
    private fun SmallButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Text(
            text,
            color = if (enabled) Colors.BROWN.color else Colors.BROWN.color.copy(alpha = 0.4f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) Colors.SECONDARY.color else Colors.SECONDARY.color.copy(alpha = 0.5f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }

    @Composable
    private fun SelectableRow(text: String, detail: String, selected: Boolean, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Colors.PRIMARY.color else Color.White.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun PanelTitle(text: String) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun Hint(text: String) {
        Box(Modifier.fillMaxWidth()) {
            Text(text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
        }
    }

    private fun entryText(entry: BoutHistoryEntry): String {
        val side = entry.competitor?.let(::sideName).orEmpty()
        return when (entry.type) {
            KerugiBoutEvents.SCORE_CANDIDATE -> Localization.getString("bout_entry_kerugi_score_candidate")
                .replaceFirst("%s", entry.seat?.toString().orEmpty())
                .replaceFirst("%s", "$side ${entry.detail}")
            KerugiBoutEvents.OPERATOR_ACTION -> Localization.getString("bout_entry_kerugi_operator_action")
                .replace("%s", "$side ${actionLabel(entry.detail)}")
            else -> Localization.getString("bout_entry_other").replace("%s", entry.detail)
        }
    }

    private fun actionLabel(detail: String): String {
        val (type, points) = detail.split(":").let { it[0] to it.getOrElse(1) { "1" } }
        return when (type) {
            KerugiOperatorActionType.THROW.name -> Localization.getString("bout_throw").replace("%d", points)
            KerugiOperatorActionType.SPIN_BONUS.name -> Localization.getString("bout_spin")
            else -> Localization.getString("bout_gamjeom")
        }
    }

    private fun statusLabel(status: BoutEntryStatus) = Localization.getString(
        when (status) {
            BoutEntryStatus.COUNTED -> "bout_status_counted"
            BoutEntryStatus.NO_QUORUM -> "bout_status_no_quorum"
            BoutEntryStatus.OUTSIDE_RUNNING_PERIOD -> "bout_status_outside"
            BoutEntryStatus.CORRECTED -> "bout_status_corrected"
            BoutEntryStatus.RESET -> "bout_status_reset"
            BoutEntryStatus.APPLIED -> "bout_status_applied"
            BoutEntryStatus.RECORDED -> "bout_status_recorded"
        },
    )

    private fun stateLabel(state: SessionState) = Localization.getString("bout_state_${state.name.lowercase()}")

    private fun sideName(side: KerugiCompetitor) =
        Localization.getString(if (side == KerugiCompetitor.BLUE) "bout_blue" else "bout_red")

    private fun opponent(side: KerugiCompetitor) = if (side == KerugiCompetitor.BLUE) KerugiCompetitor.RED else KerugiCompetitor.BLUE

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
}
