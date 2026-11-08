package org.mass.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mass.Server
import org.mass.applications.ApplicationsHistory
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionOfficials
import org.mass.draw.CourtPanel
import org.mass.draw.CourtPlan
import org.mass.draw.CourtsBoard
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawRejection
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.protocol.ProtocolDocument
import org.mass.ui.button.ButtonComponent
import org.mass.ui.court.AllCategoriesComponent
import org.mass.ui.court.CourtPanelComponent
import org.mass.ui.input.TextInputComponent
import org.mass.ui.protocol.ProtocolFiles
import org.mass.ui.screen_header.ScreenHeaderComponent
import org.mass.ui.toast.Toast
import org.mass.ui.toast.ToastComponent
import org.mass.ui.tooltip.TooltipComponent

/**
 * «Площадки и протоколы» (I4b; ADR-008 §1, §6, §8, §9, §11). The left column sets the number of courts, the court this
 * desktop judges, the competition settings of the protocols and downloads all protocols; the middle one lists every
 * category court by court with its progress and numbers («Все категории») and moves the selected one; the right one is the
 * court panel of this desktop with the current and the next bout.
 */
object CourtsScreen : Screen {
    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val courts = remember(runtimeState) { Server.runtime.competitionCourts }
        val applications = remember(runtimeState) { Server.runtime.competitionApplications }
        val history by (applications?.history ?: remember { MutableStateFlow(ApplicationsHistory(emptyList(), null)) }).collectAsState()
        val revision by (courts?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val board = remember(history, revision, courts) { courts?.board() ?: CourtsBoard.Empty }
        val panel = remember(board) { courts?.courtPanel() ?: CourtPanel.Empty }
        val scope = rememberCoroutineScope()

        var busy by remember { mutableStateOf(false) }
        var toast by remember { mutableStateOf<Toast?>(null) }
        var selectedId by remember { mutableStateOf<String?>(null) }

        fun run(action: suspend () -> Unit) {
            busy = true
            scope.launch {
                try {
                    action()
                } catch (exception: Exception) {
                    toast = Toast(exception.message ?: exception::class.simpleName.orEmpty(), error = true)
                } finally {
                    busy = false
                }
            }
        }

        /** A court command off the UI thread; a click while one runs is ignored, a refusal or failed write is shown. */
        fun command(success: String? = null, action: CompetitionCourtsService.() -> DrawOutcome) {
            val service = courts ?: return
            if (busy) return
            run {
                when (val outcome = withContext(Dispatchers.IO) { service.action() }) {
                    DrawOutcome.Done -> success?.let { toast = Toast(it) }
                    is DrawOutcome.Rejected -> toast = Toast(rejection(outcome.reason), error = true)
                    is DrawOutcome.Failed -> toast = Toast(outcome.diagnostic, error = true)
                }
            }
        }

        fun download() {
            val current = history.current?.applications ?: return
            val document = ProtocolDocument.of(board, current) ?: return
            val file = ProtocolFiles.choose(Localization.getString("protocol_file_all")) ?: return
            run {
                withContext(Dispatchers.IO) { ProtocolFiles.write(document, file) }
                toast = Toast(Localization.getString("protocol_saved").replace("%s", file.absolutePath))
            }
        }

        val active = courts != null && board.competitionId != null
        val selected = selectedId?.let(board.draws::category)

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                    Panel(Modifier.weight(0.28f)) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) {
                            Card {
                                CardTitle(Localization.getString("courts_title"))
                                Stepper(
                                    title = Localization.getString("courts_count"),
                                    value = board.plan.count.toString(),
                                    canDecrease = active && board.plan.count > 1,
                                    canIncrease = active && board.plan.count < CourtPlan.MAX_COURTS,
                                    decreaseHint = Localization.getString("courts_fewer"),
                                    increaseHint = Localization.getString("courts_more"),
                                    onDecrease = { command { setCourtCount(board.plan.count - 1) } },
                                    onIncrease = { command { setCourtCount(board.plan.count + 1) } },
                                )
                                Stepper(
                                    title = Localization.getString("courts_local"),
                                    value = Localization.getString("courts_court").replace("%d", board.localCourt.toString()),
                                    canDecrease = active && board.localCourt > 1,
                                    canIncrease = active && board.localCourt < board.plan.count,
                                    decreaseHint = Localization.getString("courts_previous"),
                                    increaseHint = Localization.getString("courts_next"),
                                    onDecrease = { command { selectLocalCourt(board.localCourt - 1) } },
                                    onIncrease = { command { selectLocalCourt(board.localCourt + 1) } },
                                )
                                Text(
                                    Localization.getString(
                                        when {
                                            board.frozen -> "brackets_numbering_frozen"
                                            board.preliminary -> "brackets_numbering_preliminary"
                                            else -> "brackets_numbering_final"
                                        },
                                    ),
                                    color = Colors.BROWN.color.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            OfficialsCard(board.officials, enabled = active) { officials ->
                                command(Localization.getString("courts_officials_saved")) { setOfficials(officials) }
                            }
                            val downloadable = active && history.current?.applications?.let { ProtocolDocument.of(board, it) } != null
                            ButtonComponent(
                                text = Localization.getString("courts_download_all"),
                                onclick = ::download,
                                enabled = downloadable,
                                tooltip = Localization.getString("courts_download_none").takeIf { !downloadable },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Panel(Modifier.weight(0.42f)) {
                        Card(Modifier.fillMaxSize()) {
                            CardTitle(Localization.getString("courts_categories"))
                            if (!active) {
                                Text(Localization.getString("courts_empty"), color = Colors.BROWN.color.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
                            } else {
                                CategoryControls(board, selectedId, ::command)
                                AllCategoriesComponent(
                                    board = board,
                                    selectedId = selected?.category?.id,
                                    onSelect = { selectedId = it.takeIf { id -> id != selectedId } },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Panel(Modifier.weight(0.30f)) {
                        Card(Modifier.fillMaxSize()) {
                            CourtPanelComponent(
                                panel = panel,
                                enabled = active,
                                onPick = { ref ->
                                    val number = board.number(ref) ?: 0
                                    command(Localization.getString("court_panel_picked_toast").replace("%d", number.toString())) {
                                        selectNextBout(ref)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                ToastComponent(toast, onDismiss = { toast = null }, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }

    /** The court and the place in the order of the selected category; the hint until one is selected. */
    @Composable
    private fun CategoryControls(board: CourtsBoard, selectedId: String?, command: (String?, CompetitionCourtsService.() -> DrawOutcome) -> Unit) {
        val state = selectedId?.let(board.draws::category)
        if (state == null) {
            Text(Localization.getString("courts_categories_hint"), color = Colors.BROWN.color.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
            return
        }
        val id = state.category.id
        val court = board.courtOf(id)
        val order = board.plan.courts[court - 1]
        val position = order.indexOf(id)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.category.title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Stepper(
                    title = Localization.getString("courts_category_court"),
                    value = Localization.getString("courts_court").replace("%d", court.toString()),
                    canDecrease = court > 1,
                    canIncrease = court < board.plan.count,
                    decreaseHint = Localization.getString("courts_previous"),
                    increaseHint = Localization.getString("courts_next"),
                    onDecrease = { moveTo(command, id, court - 1) },
                    onIncrease = { moveTo(command, id, court + 1) },
                    modifier = Modifier.weight(1f),
                )
                Stepper(
                    title = Localization.getString("courts_order"),
                    value = "${position + 1} / ${order.size}",
                    canDecrease = position > 0,
                    canIncrease = position < order.size - 1,
                    decreaseHint = Localization.getString("courts_move_up"),
                    increaseHint = Localization.getString("courts_move_down"),
                    onDecrease = { command(null) { placeCategory(id, court, position - 1) } },
                    onIncrease = { command(null) { placeCategory(id, court, position + 1) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    private fun moveTo(command: (String?, CompetitionCourtsService.() -> DrawOutcome) -> Unit, categoryId: String, court: Int) {
        command(Localization.getString("courts_moved").replace("%d", court.toString())) {
            placeCategory(categoryId, court, Int.MAX_VALUE)
        }
    }

    /** Place, chief judge and chief secretary; «Сохранить» journals them once they differ from the saved ones. */
    @Composable
    private fun OfficialsCard(saved: CompetitionOfficials, enabled: Boolean, onSave: (CompetitionOfficials) -> Unit) {
        var place by remember(saved) { mutableStateOf(saved.place) }
        var chiefJudge by remember(saved) { mutableStateOf(saved.chiefJudge) }
        var chiefSecretary by remember(saved) { mutableStateOf(saved.chiefSecretary) }
        val edited = CompetitionOfficials(place, chiefJudge, chiefSecretary)
        val tooLong = { value: String ->
            Localization.getString("brackets_draw_rejected_invalid_officials").takeIf { value.trim().length > CompetitionOfficials.MAX_LENGTH }
        }
        Card {
            CardTitle(Localization.getString("courts_officials"))
            listOf(
                Triple("courts_place", place) { value: String -> place = value },
                Triple("courts_chief_judge", chiefJudge) { value: String -> chiefJudge = value },
                Triple("courts_chief_secretary", chiefSecretary) { value: String -> chiefSecretary = value },
            ).forEach { (key, value, onChange) ->
                TextInputComponent(
                    labelText = Localization.getString(key),
                    inputValue = value,
                    onChange = onChange,
                    validator = tooLong,
                    labelColor = Colors.BROWN.color,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ButtonComponent(
                text = Localization.getString("courts_officials_save"),
                onclick = { onSave(edited) },
                enabled = enabled && edited.normalised() != saved && edited.normalised().valid,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** A titled value with «−» and «+» on both sides; the tooltips name what they change. */
    @Composable
    private fun Stepper(
        title: String,
        value: String,
        canDecrease: Boolean,
        canIncrease: Boolean,
        decreaseHint: String,
        increaseHint: String,
        onDecrease: () -> Unit,
        onIncrease: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(Color.White.copy(alpha = 0.6f)).padding(4.dp),
            ) {
                StepButton("−", decreaseHint, canDecrease, onDecrease)
                Text(
                    value,
                    color = Colors.PRIMARY.color,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                StepButton("+", increaseHint, canIncrease, onIncrease)
            }
        }
    }

    @Composable
    private fun StepButton(symbol: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
        TooltipComponent(hint) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(ROW_SHAPE)
                    .background(if (enabled) Colors.PRIMARY.color else Colors.PRIMARY.color.copy(alpha = 0.35f))
                    .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick) else Modifier)
                    .semantics { contentDescription = hint },
            ) {
                Text(symbol, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = modifier.fillMaxHeight().clip(PANEL_SHAPE).background(Colors.GRAY.color).padding(20.dp),
            content = content,
        )
    }

    @Composable
    private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier
                .fillMaxWidth()
                .clip(CARD_SHAPE)
                .background(Colors.SECONDARY.color)
                .border(1.dp, Color.White.copy(alpha = 0.4f), CARD_SHAPE)
                .padding(12.dp),
            content = content,
        )
    }

    @Composable
    private fun CardTitle(text: String) {
        Text(text, color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }

    private fun rejection(reason: DrawRejection) = Localization.getString(
        when (reason) {
            DrawRejection.NO_COMPETITION -> "brackets_draw_rejected_no_competition"
            DrawRejection.UNKNOWN_CATEGORY -> "brackets_draw_rejected_unknown_category"
            DrawRejection.STARTED -> "courts_rejected_started"
            DrawRejection.NOTHING_TO_CONFIRM -> "brackets_draw_rejected_nothing_to_confirm"
            DrawRejection.NOT_CONFIRMED -> "brackets_draw_rejected_not_confirmed"
            DrawRejection.UNKNOWN_ENTRY -> "brackets_draw_rejected_unknown_entry"
            DrawRejection.INVALID_COURT -> "brackets_draw_rejected_invalid_court"
            DrawRejection.UNKNOWN_BOUT -> "brackets_draw_rejected_unknown_bout"
            DrawRejection.INVALID_OFFICIALS -> "brackets_draw_rejected_invalid_officials"
        },
    )

    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
    private val CARD_SHAPE = RoundedCornerShape(16.dp)
    private val ROW_SHAPE = RoundedCornerShape(8.dp)
}
