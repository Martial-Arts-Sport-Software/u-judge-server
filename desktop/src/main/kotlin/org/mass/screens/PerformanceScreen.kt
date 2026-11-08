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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import org.mass.bout.ConnectedJudge
import org.mass.bout.CourtBoutOutcome
import org.mass.bout.CourtBouts
import org.mass.domain.AggregationFormula
import org.mass.domain.EventId
import org.mass.domain.SessionState
import org.mass.draw.CourtItem
import org.mass.draw.CourtPanel
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawRejection
import org.mass.enums.Colors
import org.mass.enums.Routes
import org.mass.locale.Localization
import org.mass.ui.RejectionText
import org.mass.performance.PerformanceCommand
import org.mass.performance.TechnicalAdjustment
import org.mass.performance.TechnicalAdjustmentKind
import org.mass.performance.TechnicalPerformance
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.button.clickWithTransition
import org.mass.ui.court.CourtPanelComponent
import org.mass.ui.dialog.ConfirmDialogComponent
import org.mass.ui.input.TextInputComponent
import org.mass.ui.screen_header.ScreenHeaderComponent

/**
 * The operator's technical performance (I5, ADR-011 §5-§9): «Следующее выступление» with the roster of connected judges
 * and the aggregation formula, then the seats with submitted/pending Sends (`TEC-014`), «Старт», the arbiter's penalties
 * and bonus with reasons, the completion with the server total and divisor, and the places of the category. Every action
 * is a journaled command of the court dispatcher, written off the UI thread; the screen only renders the projection.
 */
object PerformanceScreen : Screen {
    private sealed interface Confirmation {
        data object Complete : Confirmation
        data object Cancel : Confirmation
        data class Adjust(val kind: TechnicalAdjustmentKind) : Confirmation
        data class Withdraw(val adjustment: TechnicalAdjustment) : Confirmation
    }

    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val court = remember(runtimeState) { Server.runtime.courtBouts }
        val performances = remember(court) { court?.performances }
        val revision by (performances?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val boutRevision by (court?.bouts?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val drawRevision by (Server.runtime.competitionDraws?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val pairingChanges by Server.runtime.pairingRequests.changes.collectAsState()
        val current = remember(performances, revision) { performances?.current() }
        val panel = remember(court, revision, boutRevision, drawRevision) { court?.panel() ?: CourtPanel.Empty }
        val connected = remember(court, pairingChanges) { court?.connectedJudges().orEmpty() }
        val registry = remember(pairingChanges) { Server.runtime.pairingRequests.operatorRegistry() }
        val standings = remember(court, revision, current?.started?.categoryId) {
            current?.let { court?.standings(it.started.categoryId) }.orEmpty()
        }
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var confirmation by remember { mutableStateOf<Confirmation?>(null) }
        var seats by remember { mutableStateOf(listOf<String>()) }
        var formula by remember { mutableStateOf(AggregationFormula.TRIMMED_MEAN) }

        fun act(block: CourtBouts.() -> CourtBoutOutcome) {
            val target = court ?: return
            if (busy) return
            scope.launch {
                busy = true
                val outcome = withContext(Dispatchers.IO) { runCatching { target.block() } }
                message = outcome.fold(
                    onSuccess = { result ->
                        when (result) {
                            is CourtBoutOutcome.Done -> null
                            is CourtBoutOutcome.Rejected -> Localization.getString("bout_rejected").replace("%s", rejection(result.code))
                            is CourtBoutOutcome.Failed -> Localization.getString("bout_failed").replace("%s", result.diagnostic)
                        }
                    },
                    onFailure = { Localization.getString("bout_failed").replace("%s", it.message ?: it::class.simpleName.orEmpty()) },
                )
                busy = false
            }
        }

        fun operator(command: (EventId) -> PerformanceCommand.FromOperator) =
            act { performanceCommand(State.judgeSurname, command(EventId.new())) }

        val active = current?.takeIf { !it.closed }
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(0.34f).fillMaxHeight().clip(PANEL_SHAPE).background(Colors.GRAY.color).padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (active == null) {
                        NextPerformance(
                            panel = panel,
                            connected = connected,
                            seats = seats,
                            onSeat = { device -> seats = if (device in seats) seats - device else (seats + device).take(MAX_SEATS) },
                            formula = formula,
                            onFormula = { formula = it },
                            enabled = !busy && court != null,
                            onPick = { ref ->
                                val courts = Server.runtime.competitionCourts ?: return@NextPerformance
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) { courts.selectNextBout(ref) }
                                    message = (outcome as? DrawOutcome.Rejected)?.let {
                                        Localization.getString(if (it.reason == DrawRejection.UNKNOWN_BOUT) "brackets_draw_rejected_unknown_bout" else "brackets_draw_rejected_no_competition")
                                    }
                                }
                            },
                            onStart = {
                                act { startPerformance(seats.filter { it in connected.map(ConnectedJudge::deviceId) }, formula, State.judgeSurname) }
                            },
                        )
                    } else {
                        Seats(active, registry.devices.associate { it.deviceId to it.connectionState })
                        ButtonComponent(
                            Localization.getString("i5_performance_cancel"),
                            ButtonStyles.Secondary,
                            onclick = { confirmation = Confirmation.Cancel },
                            enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = Localization.getString("i5_performance_cancel") },
                        )
                    }
                    message?.let { Text(it, color = Color(0xFFFFB4B4), style = MaterialTheme.typography.bodyMedium) }
                }
                Spacer(Modifier.width(25.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(0.66f).fillMaxHeight().clip(PANEL_SHAPE).background(Colors.GRAY.color).padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (current == null) {
                        Text(Localization.getString("i5_performance_none"), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    } else {
                        Scoreboard(
                            performance = current,
                            enabled = !busy && !current.closed,
                            onBegin = { operator { PerformanceCommand.Begin(it, current.sessionId) } },
                            onAdjust = { confirmation = Confirmation.Adjust(it) },
                            onWithdraw = { confirmation = Confirmation.Withdraw(it) },
                            onComplete = { confirmation = Confirmation.Complete },
                        )
                        Standings(standings.map { Triple(it.place, it.key, it.panelVote) })
                    }
                }
            }
        }

        val performance = current
        when (val pending = confirmation) {
            null -> Unit
            Confirmation.Complete -> {
                val missing = performance?.missingSeats().orEmpty()
                if (missing.isEmpty()) {
                    val result = performance?.currentResult()
                    ConfirmDialogComponent(
                        title = Localization.getString("i5_performance_complete_title"),
                        text = Localization.getString("i5_performance_complete_text").replace("%s", result?.let { tenths(it.totalTenths) } ?: "—"),
                        confirmText = Localization.getString("i5_performance_complete"),
                        cancelText = Localization.getString("bout_back"),
                        onConfirm = {
                            confirmation = null
                            if (performance != null) operator { PerformanceCommand.Complete(it, performance.sessionId) }
                        },
                        onCancel = { confirmation = null },
                    )
                } else {
                    ReasonDialog(
                        title = Localization.getString("i5_performance_complete_missing_title"),
                        text = Localization.getString("i5_performance_complete_missing_text").replace("%s", missing.joinToString(", ")),
                        confirmText = Localization.getString("i5_performance_complete"),
                        onConfirm = { reason ->
                            confirmation = null
                            if (performance != null) operator { PerformanceCommand.Complete(it, performance.sessionId, reason) }
                        },
                        onCancel = { confirmation = null },
                    )
                }
            }
            Confirmation.Cancel -> ReasonDialog(
                title = Localization.getString("i5_performance_cancel_title"),
                text = Localization.getString("i5_performance_cancel_text"),
                confirmText = Localization.getString("i5_performance_cancel"),
                onConfirm = { reason ->
                    confirmation = null
                    if (performance != null) operator { PerformanceCommand.Cancel(it, performance.sessionId, reason) }
                },
                onCancel = { confirmation = null },
            )
            is Confirmation.Adjust -> ReasonDialog(
                title = Localization.getString("i5_performance_adjust_${pending.kind.name.lowercase()}_title"),
                text = Localization.getString("i5_performance_adjust_text"),
                confirmText = Localization.getString("i5_performance_adjust_${pending.kind.name.lowercase()}"),
                onConfirm = { reason ->
                    confirmation = null
                    if (performance != null) operator { PerformanceCommand.Adjust(it, performance.sessionId, pending.kind, reason) }
                },
                onCancel = { confirmation = null },
            )
            is Confirmation.Withdraw -> ReasonDialog(
                title = Localization.getString("i5_performance_withdraw_title"),
                text = Localization.getString("i5_performance_withdraw_text").replace("%s", pending.adjustment.reason),
                confirmText = Localization.getString("i5_performance_withdraw"),
                onConfirm = { reason ->
                    confirmation = null
                    if (performance != null) {
                        operator { PerformanceCommand.WithdrawAdjustment(it, performance.sessionId, pending.adjustment.eventId, reason) }
                    }
                },
                onCancel = { confirmation = null },
            )
        }
    }

    /** The court panel, the judges in seat order and the formula fixed at «Начать» (`TEC-010`). */
    @Composable
    private fun NextPerformance(
        panel: CourtPanel,
        connected: List<ConnectedJudge>,
        seats: List<String>,
        onSeat: (String) -> Unit,
        formula: AggregationFormula,
        onFormula: (AggregationFormula) -> Unit,
        enabled: Boolean,
        onPick: (org.mass.draw.ItemRef) -> Unit,
        onStart: () -> Unit,
    ) {
        val seated = seats.count { device -> connected.any { it.deviceId == device } }
        val startable = enabled && panel.next is CourtItem.Performance && seated in formula.minimumScores..MAX_SEATS
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Colors.SECONDARY.color).padding(14.dp)) {
            CourtPanelComponent(panel, onPick, Modifier.fillMaxWidth().height(260.dp), enabled = enabled, onStart = onStart.takeIf { startable })
        }
        if (panel.next is CourtItem.Bout) {
            Hint(Localization.getString("i5_performance_next_is_bout"))
            ButtonComponent(
                Localization.getString("i5_performance_open_bout"),
                ButtonStyles.Secondary,
                onclick = { clickWithTransition(Routes.BOUT) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (panel.next == null) Hint(Localization.getString("i5_performance_select_empty"))
        PanelTitle(Localization.getString("i5_performance_formula_title"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AggregationFormula.entries.forEach { option ->
                Box(Modifier.weight(1f)) {
                    SelectableRow(
                        text = Localization.getString("i5_formula_${option.code}"),
                        detail = Localization.getString("i5_formula_${option.code}_detail"),
                        selected = option == formula,
                        onClick = { onFormula(option) },
                    )
                }
            }
        }
        PanelTitle(Localization.getString("bout_judges_title"))
        if (connected.isEmpty()) {
            Hint(Localization.getString("bout_judges_empty"))
        } else {
            Hint(Localization.getString("i5_performance_judges_hint").replace("%d", formula.minimumScores.toString()))
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
    }

    /** Every seat with its connection and whether its final Send arrived (`TEC-014`). */
    @Composable
    private fun Seats(performance: TechnicalPerformance, connection: Map<String, DeviceConnectionState?>) {
        PanelTitle(Localization.getString("bout_seats_title"))
        performance.seats.forEach { seat ->
            val online = connection[seat.deviceId] == DeviceConnectionState.CONNECTED
            val sent = performance.sends[seat.number]
            SelectableRow(
                text = Localization.getString("bout_seat").replaceFirst("%d", seat.number.toString()).replaceFirst("%s", seat.surname),
                detail = Localization.getString(if (sent != null) "i5_performance_seat_submitted" else "i5_performance_seat_pending") + " · " +
                    Localization.getString(if (online) "bout_seat_connected" else "bout_seat_disconnected"),
                selected = sent != null,
                onClick = {},
            )
        }
        Hint(
            Localization.getString("i5_performance_settings")
                .replaceFirst("%s", Localization.getString("i5_formula_${performance.formula.code}"))
                .replaceFirst("%d", performance.seats.size.toString()),
        )
    }

    @Composable
    private fun Scoreboard(
        performance: TechnicalPerformance,
        enabled: Boolean,
        onBegin: () -> Unit,
        onAdjust: (TechnicalAdjustmentKind) -> Unit,
        onWithdraw: (TechnicalAdjustment) -> Unit,
        onComplete: () -> Unit,
    ) {
        val started = performance.started
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Localization.getString("i5_performance_number").replace("%d", started.performanceNumber.toString()) + " · " +
                    Localization.getString("i5_discipline_${performance.discipline.code}") + " · " + started.categoryTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(Localization.getString("bout_state_${performance.state.name.lowercase()}"), color = Colors.SECONDARY.color, style = MaterialTheme.typography.titleSmall)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Colors.PRIMARY.color).padding(12.dp),
        ) {
            Text(started.entry.label, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            val result = performance.result()
            Text(
                result?.let { tenths(it.totalTenths) } ?: "—",
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = Localization.getString("i5_performance_total") },
            )
            Text(
                Localization.getString("i5_performance_criteria")
                    .replaceFirst("%d", started.techniqueCriteria.toString())
                    .replaceFirst("%d", started.presentationCriteria.toString()),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            result?.let {
                Text(
                    Localization.getString("i5_performance_result")
                        .replaceFirst("%s", tenths(it.aggregateTenths))
                        .replaceFirst("%d", it.divisor.toString())
                        .replaceFirst("%s", signedTenths(it.adjustmentTenths)),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        PanelTitle(Localization.getString("i5_performance_sends_title"))
        if (performance.sends.isEmpty()) Hint(Localization.getString("i5_performance_sends_empty"))
        performance.sends.values.sortedBy { it.seat }.forEach { send ->
            Hint(
                Localization.getString("i5_performance_send")
                    .replaceFirst("%d", send.seat.toString())
                    .replaceFirst("%s", tenths(send.totalTenths))
                    .replaceFirst("%s", tenths(send.vector.techniqueTenths))
                    .replaceFirst("%s", tenths(send.vector.presentationTenths))
                    .replaceFirst("%s", tenths(send.vector.deductionTenths)),
            )
        }
        val running = enabled && performance.state == SessionState.RUNNING
        PanelTitle(Localization.getString("i5_performance_adjustments_title"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(Localization.getString("i5_performance_adjust_penalty"), running, Modifier.weight(1f)) { onAdjust(TechnicalAdjustmentKind.PENALTY) }
            if (performance.discipline.bonusAllowed) {
                val bonusTaken = performance.effectiveAdjustments().any { it.kind == TechnicalAdjustmentKind.BONUS }
                SmallButton(Localization.getString("i5_performance_adjust_bonus"), running && !bonusTaken, Modifier.weight(1f)) {
                    onAdjust(TechnicalAdjustmentKind.BONUS)
                }
            }
        }
        performance.adjustments.forEach { adjustment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    signedTenths(adjustment.amountTenths) + " · " + adjustment.reason + " · " + adjustment.author +
                        if (adjustment.withdrawn) " · " + Localization.getString("i5_performance_withdrawn") else "",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!adjustment.withdrawn) {
                    SmallButton(Localization.getString("i5_performance_withdraw"), running, Modifier.width(140.dp)) { onWithdraw(adjustment) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(Localization.getString("i5_performance_begin"), enabled && performance.state == SessionState.PREPARED, Modifier.weight(1f), onBegin)
            SmallButton(Localization.getString("i5_performance_complete"), running, Modifier.weight(1f), onComplete)
        }
    }

    /** The places of the category's completed performances (`TEC-013`); full ties go to the panel vote. */
    @Composable
    private fun Standings(places: List<Triple<Int, TechnicalPerformance, Boolean>>) {
        PanelTitle(Localization.getString("i5_performance_standings_title"))
        if (places.isEmpty()) Hint(Localization.getString("i5_performance_standings_empty"))
        places.forEach { (place, performance, vote) ->
            Hint(
                Localization.getString("i5_performance_place")
                    .replaceFirst("%d", place.toString())
                    .replaceFirst("%s", performance.started.entry.label)
                    .replaceFirst("%s", performance.result()?.let { tenths(it.totalTenths) } ?: "—") +
                    if (vote) " · " + Localization.getString("i5_performance_panel_vote") else "",
            )
        }
    }

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

    /** Tenths as the FHR protocols print them: `6.4`. */
    fun tenths(value: Int): String = "${value / 10}.${value % 10}"

    private fun signedTenths(value: Int): String = (if (value < 0) "−" else "+") + tenths(kotlin.math.abs(value))

    private fun rejection(code: String): String = RejectionText.of(code)

    private const val MAX_SEATS = 7
    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
}
