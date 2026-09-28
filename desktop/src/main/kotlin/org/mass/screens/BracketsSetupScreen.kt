package org.mass.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.mass.Server
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationError
import org.mass.applications.ApplicationFileInput
import org.mass.applications.ApplicationsHistory
import org.mass.applications.Athlete
import org.mass.applications.ClearOutcome
import org.mass.applications.CompetitionApplications
import org.mass.applications.ImportOutcome
import org.mass.applications.ImportPreparation
import org.mass.applications.ImportRecord
import org.mass.draw.CategoryDraw
import org.mass.draw.CategoryDrawState
import org.mass.draw.CategoryStatus
import org.mass.draw.CompetitionDrawService
import org.mass.draw.DrawBoard
import org.mass.draw.DrawFormat
import org.mass.draw.DrawMethod
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawRejection
import org.mass.draw.DrawSystem
import org.mass.draw.SeparationCriterion
import org.mass.draw.drawKey
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.bracket.BracketComponent
import org.mass.ui.dialog.ConfirmDialogComponent
import org.mass.ui.input.TextInputComponent
import org.mass.ui.screen_header.ScreenHeaderComponent
import org.mass.ui.toast.Toast
import org.mass.ui.toast.ToastComponent
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.bracket_icon
import u_judge_server.desktop.generated.resources.cross_icon
import u_judge_server.desktop.generated.resources.empty_categories
import u_judge_server.desktop.generated.resources.empty_preview
import u_judge_server.desktop.generated.resources.fullscreen_icon
import u_judge_server.desktop.generated.resources.list_icon
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * «Настройка турнирных сеток» (I4a, I4b; ADR-005, ADR-007, ADR-008). The upload tab takes the competition name and date and
 * several application files; «Загрузить» checks them against «Требования к заполнению заявок» and imports them in one step.
 * The history tab lists every import, lets the operator view or restore one, reset them all, and holds the draw of the
 * current import: previews, «Перемешать», the system per category, «Подтвердить», and swaps in the preview list.
 */
object BracketsSetupScreen : Screen {
    private enum class Tab(val key: String) { UPLOAD("brackets_tab_upload"), HISTORY("brackets_tab_history") }

    /** A confirmation the operator must give before the journal changes. */
    private sealed interface Confirmation {
        data class Replace(val applications: CompetitionApplications, val current: ImportRecord) : Confirmation
        data class Restore(val record: ImportRecord, val number: Int) : Confirmation
        data object Reset : Confirmation

        /** «Подтвердить» replaces the confirmed draw of [categories] categories that were shuffled again. */
        data class ReplaceDraws(val categories: Int) : Confirmation
    }

    /**
     * Unsaved swaps of one category: two clicks swap two entries locally; «Сохранить» sends them all, «Назад без сохранения»
     * drops them. [swapped] is the last pair, highlighted as in HapkiPRO.
     */
    private data class Editing(
        val categoryId: String,
        val draw: CategoryDraw,
        val swaps: List<Pair<String, String>> = emptyList(),
        val picked: String? = null,
        val swapped: Pair<String, String>? = null,
    )

    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val service = remember(runtimeState) { Server.runtime.competitionApplications }
        val history by (service?.history ?: remember { MutableStateFlow(ApplicationsHistory(emptyList(), null)) }).collectAsState()
        val draws = remember(runtimeState) { Server.runtime.competitionDraws }
        val revision by (draws?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val board = remember(history, revision, draws) { draws?.board() ?: DrawBoard(null, DrawMethod(), emptyList()) }
        val scope = rememberCoroutineScope()

        var tab by remember { mutableStateOf(Tab.UPLOAD) }
        var competitionName by remember { mutableStateOf(history.current?.applications?.competitionName.orEmpty()) }
        var dateText by remember { mutableStateOf(history.current?.applications?.competitionDate?.let(DATE_FORMAT::format).orEmpty()) }
        var files by remember { mutableStateOf(listOf<File>()) }
        var errors by remember { mutableStateOf<List<ApplicationError>?>(null) }
        var viewedEventId by remember { mutableStateOf<String?>(null) }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var toast by remember { mutableStateOf<Toast?>(null) }
        var confirmation by remember { mutableStateOf<Confirmation?>(null) }
        var bracketView by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Editing?>(null) }

        val formReady = nameError(competitionName) == null && parseDate(dateText) != null

        fun show(outcome: ImportOutcome) {
            when (outcome) {
                is ImportOutcome.Imported -> {
                    viewedEventId = null
                    toast = Toast(
                        summary("brackets_imported", outcome.record.applications) +
                            (outcome.backup?.let { "\n" + Localization.getString("brackets_backup").replace("%s", it.toString()) } ?: ""),
                    )
                }
                ImportOutcome.AlreadyImported -> toast = Toast(Localization.getString("brackets_already_imported"))
                is ImportOutcome.ReplaceNeedsConfirmation -> Unit
                is ImportOutcome.Failed -> toast = Toast(outcome.diagnostic, error = true)
            }
        }

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

        /** Checks the selected files and imports them when they pass; a failed check only shows the report. */
        fun load() {
            val importService = service ?: return
            val date = parseDate(dateText) ?: return
            run {
                val preparation = withContext(Dispatchers.IO) {
                    importService.prepare(competitionName, date, files.map { ApplicationFileInput(it.name, it.readBytes()) })
                }
                when (preparation) {
                    is ImportPreparation.Invalid -> {
                        errors = preparation.errors
                        toast = Toast(Localization.getString("brackets_check_failed").replace("%d", preparation.errors.size.toString()), error = true)
                    }
                    is ImportPreparation.Valid -> {
                        errors = null
                        val outcome = withContext(Dispatchers.IO) { importService.import(preparation.applications, replaceConfirmed = false) }
                        if (outcome is ImportOutcome.ReplaceNeedsConfirmation) {
                            confirmation = Confirmation.Replace(preparation.applications, outcome.current)
                        }
                        show(outcome)
                    }
                }
            }
        }

        /** Runs a draw command off the UI thread and reports a refusal or a failed write as an error toast. */
        fun drawCommand(success: String? = null, onDone: () -> Unit = {}, action: CompetitionDrawService.() -> DrawOutcome) {
            val drawService = draws ?: return
            run {
                when (val outcome = withContext(Dispatchers.IO) { drawService.action() }) {
                    DrawOutcome.Done -> {
                        onDone()
                        success?.let { toast = Toast(it) }
                    }
                    is DrawOutcome.Rejected -> toast = Toast(rejection(outcome.reason), error = true)
                    is DrawOutcome.Failed -> toast = Toast(outcome.diagnostic, error = true)
                }
            }
        }

        fun confirmDraws() {
            val count = board.previews
            drawCommand(Localization.getString("brackets_draw_confirmed").replace("%d", count.toString())) { confirm() }
        }

        fun confirm(action: Confirmation) {
            val importService = service ?: return
            confirmation = null
            run {
                when (action) {
                    is Confirmation.Replace -> show(withContext(Dispatchers.IO) { importService.import(action.applications, replaceConfirmed = true) })
                    is Confirmation.Restore -> show(withContext(Dispatchers.IO) { importService.restore(action.record.eventId, replaceConfirmed = true) })
                    Confirmation.Reset -> when (val outcome = withContext(Dispatchers.IO) { importService.clear() }) {
                        is ClearOutcome.Cleared -> {
                            viewedEventId = null
                            toast = Toast(
                                Localization.getString("brackets_reset_done") +
                                    (outcome.backup?.let { "\n" + Localization.getString("brackets_backup").replace("%s", it.toString()) } ?: ""),
                            )
                        }
                        ClearOutcome.NothingToClear -> Unit
                        is ClearOutcome.Failed -> toast = Toast(outcome.diagnostic, error = true)
                    }
                    is Confirmation.ReplaceDraws -> Unit
                }
            }
        }

        val viewed = history.imports.firstOrNull { it.eventId == viewedEventId } ?: history.current
        val shown = viewed?.applications
        val selected = shown?.categories?.firstOrNull { it.id == selectedCategoryId } ?: shown?.categories?.firstOrNull()
        // The draw belongs to the current import; an import opened from the history shows only its athletes.
        val drawing = viewed != null && viewed.eventId == history.currentEventId
        val drawStates = if (drawing) board.categories.associateBy { it.category.id } else emptyMap()
        val selectedState = selected?.let { drawStates[it.id] }
        val editingHere = editing?.takeIf { it.categoryId == selected?.id && selectedState?.shown != null }
        val shownDraw = editingHere?.draw ?: selectedState?.shown
        val bracketAvailable = shownDraw?.format == DrawFormat.ELIMINATION || shownDraw?.format == DrawFormat.ROUND_ROBIN

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                    // Both panels use the devices screen's 20 dp padding and gaps.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .clip(PANEL_SHAPE)
                            .background(Colors.GRAY.color)
                            .padding(20.dp),
                    ) {
                        TabSwitch(tab, onSelect = { tab = it })
                        when (tab) {
                            Tab.UPLOAD -> UploadTab(
                                competitionName = competitionName,
                                onNameChange = { competitionName = it },
                                dateText = dateText,
                                onDateChange = { dateText = it },
                                files = files,
                                formReady = formReady && service != null,
                                busy = busy,
                                onChooseFiles = {
                                    chooseFiles()?.let { chosen ->
                                        files = (files + chosen).distinctBy { it.absolutePath }
                                        errors = null
                                    }
                                },
                                onRemoveFile = { file ->
                                    files = files - file
                                    errors = null
                                },
                                onLoad = ::load,
                            )
                            Tab.HISTORY -> HistoryTab(
                                history = history,
                                viewedEventId = viewed?.eventId,
                                onView = { record ->
                                    viewedEventId = record.eventId
                                    errors = null
                                },
                                onRestore = { record -> confirmation = Confirmation.Restore(record, history.imports.indexOf(record) + 1) },
                                onReset = { confirmation = Confirmation.Reset },
                                enabled = !busy && service != null,
                                drawBlock = {
                                    DrawBlock(
                                        board = board.takeIf { drawing },
                                        selected = selectedState,
                                        enabled = !busy && draws != null && editingHere == null,
                                        onMethod = { method -> drawCommand { setMethod(method) } },
                                        onSystem = { system -> selectedState?.let { state -> drawCommand { setSystem(state.category.id, system) } } },
                                        onDrawAll = { drawCommand { drawAll() } },
                                        onShuffle = { selectedState?.let { state -> drawCommand { shuffle(state.category.id) } } },
                                        onConfirm = {
                                            val replacing = board.categories.count { state ->
                                                state.preview != null && state.confirmed?.draw?.entryKeys?.toSet() ==
                                                    state.category.entries.map { it.drawKey }.toSet()
                                            }
                                            if (replacing > 0) confirmation = Confirmation.ReplaceDraws(replacing) else confirmDraws()
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(25.dp))
                    Row(
                        modifier = Modifier
                            .weight(0.7f)
                            .fillMaxHeight()
                            .clip(PANEL_SHAPE)
                            .background(Colors.GRAY.color)
                            .padding(20.dp),
                    ) {
                        ListCard(
                            title = Localization.getString("brackets_categories"),
                            subtitle = viewed?.let { recordLabel(history, it) },
                            modifier = Modifier.weight(0.45f),
                            empty = shown == null || shown.categories.isEmpty(),
                            emptyState = { EmptyState(Res.drawable.empty_categories, "brackets_categories_empty", "brackets_categories_empty_hint") },
                        ) {
                            ScrollableList(rememberLazyListState(), Colors.SECONDARY.color) { state ->
                                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(shown?.categories.orEmpty(), key = { _, category -> category.id }) { _, category ->
                                        CategoryRow(
                                            category,
                                            state = drawStates[category.id],
                                            selected = category.id == selected?.id,
                                            onClick = {
                                                if (category.id != selected?.id) editing = null
                                                selectedCategoryId = category.id
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        val errorList = errors
                        ListCard(
                            title = if (errorList != null) {
                                Localization.getString("brackets_errors").replace("%d", errorList.size.toString())
                            } else {
                                Localization.getString("brackets_preview")
                            },
                            subtitle = if (errorList != null) Localization.getString("brackets_errors_hint") else selected?.title,
                            modifier = Modifier.weight(0.55f),
                            empty = errorList == null && selected == null,
                            emptyState = { EmptyState(Res.drawable.empty_preview, "brackets_preview_empty", "brackets_preview_empty_hint") },
                            // List/bracket and full screen on their own line; the bracket view opens once the draw made one.
                            toolbar = if (errorList == null) {
                                {
                                    PreviewNavigation(
                                        bracketAvailable = bracketAvailable,
                                        bracketSelected = bracketView && bracketAvailable,
                                        onSelect = { bracket ->
                                            bracketView = bracket
                                            if (bracket) editing = null
                                        },
                                    )
                                    if (selectedState?.shown != null && shownDraw != null && !(bracketView && bracketAvailable)) {
                                        Spacer(Modifier.height(8.dp))
                                        EditBar(
                                            editing = editingHere,
                                            locked = selectedState.status == CategoryStatus.STARTED,
                                            editable = shownDraw.entryKeys.size > 1,
                                            enabled = !busy,
                                            onEdit = { editing = Editing(selectedState.category.id, shownDraw) },
                                            onSave = {
                                                val pending = editingHere ?: return@EditBar
                                                if (pending.swaps.isEmpty()) {
                                                    editing = null
                                                } else {
                                                    drawCommand(Localization.getString("brackets_edit_saved"), onDone = { editing = null }) {
                                                        swap(pending.categoryId, pending.swaps)
                                                    }
                                                }
                                            },
                                            onBack = { editing = null },
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        ) {
                            when {
                                errorList != null -> ErrorList(errorList)
                                selected == null -> Unit
                                shownDraw == null -> CategoryPreview(selected)
                                bracketView && bracketAvailable -> BracketComponent(
                                    draw = shownDraw,
                                    entries = selected.entries,
                                    clashes = selectedState?.clashes.orEmpty().flatMap { listOf(it.first, it.second) }.toSet(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                else -> DrawList(
                                    category = selected,
                                    draw = shownDraw,
                                    state = selectedState,
                                    editing = editingHere,
                                    onPick = { key ->
                                        val pending = editingHere ?: return@DrawList
                                        val picked = pending.picked
                                        editing = when {
                                            picked == null -> pending.copy(picked = key, swapped = null)
                                            picked == key -> pending.copy(picked = null)
                                            else -> pending.copy(
                                                draw = pending.draw.swap(picked, key),
                                                swaps = pending.swaps + (picked to key),
                                                picked = null,
                                                swapped = picked to key,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                ToastComponent(toast, onDismiss = { toast = null }, modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        when (val pending = confirmation) {
            is Confirmation.Replace -> ConfirmDialogComponent(
                title = Localization.getString("brackets_replace_title"),
                text = Localization.getString("brackets_replace_text")
                    .replaceFirst("%s", pending.current.applications.competitionName)
                    .replaceFirst("%s", pending.current.applications.athleteCount.toString()),
                confirmText = Localization.getString("brackets_replace"),
                cancelText = Localization.getString("devices_cancel"),
                onConfirm = { confirm(pending) },
                onCancel = {
                    confirmation = null
                    toast = Toast(Localization.getString("brackets_replace_cancelled"))
                },
            )
            is Confirmation.Restore -> ConfirmDialogComponent(
                title = Localization.getString("brackets_restore_title").replace("%d", pending.number.toString()),
                text = Localization.getString("brackets_restore_text")
                    .replaceFirst("%s", pending.record.applications.competitionName)
                    .replaceFirst("%s", pending.record.applications.athleteCount.toString()),
                confirmText = Localization.getString("brackets_restore"),
                cancelText = Localization.getString("devices_cancel"),
                onConfirm = { confirm(pending) },
                onCancel = { confirmation = null },
            )
            Confirmation.Reset -> ConfirmDialogComponent(
                title = Localization.getString("brackets_reset_title"),
                text = Localization.getString("brackets_reset_text"),
                confirmText = Localization.getString("brackets_reset"),
                cancelText = Localization.getString("devices_cancel"),
                onConfirm = { confirm(Confirmation.Reset) },
                onCancel = { confirmation = null },
            )
            is Confirmation.ReplaceDraws -> ConfirmDialogComponent(
                title = Localization.getString("brackets_replace_draws_title"),
                text = Localization.getString("brackets_replace_draws_text").replace("%d", pending.categories.toString()),
                confirmText = Localization.getString("brackets_replace_draws"),
                cancelText = Localization.getString("devices_cancel"),
                onConfirm = {
                    confirmation = null
                    confirmDraws()
                },
                onCancel = { confirmation = null },
            )
            null -> Unit
        }
    }

    @Composable
    private fun TabSwitch(selected: Tab, onSelect: (Tab) -> Unit) {
        SegmentedControl(
            segments = Tab.entries.map { Segment(Localization.getString(it.key)) },
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(Tab.entries[it]) },
            dark = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private data class Segment(val label: String, val icon: DrawableResource? = null, val enabled: Boolean = true)

    /**
     * Tabs of the mockup: the selected segment is primary; [dark] is for the gray panel, otherwise the light cards.
     * A disabled segment is faded and does not react.
     */
    @Composable
    private fun SegmentedControl(
        segments: List<Segment>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        dark: Boolean,
        modifier: Modifier = Modifier,
        fill: Boolean = true,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = modifier.clip(ROW_SHAPE).background(if (dark) CARD_COLOR else Color.White.copy(alpha = 0.6f)).padding(4.dp),
        ) {
            segments.forEachIndexed { index, segment ->
                val active = index == selectedIndex
                val content = when {
                    active -> Color.White
                    !segment.enabled -> (if (dark) LAVENDER_TEXT else Colors.BROWN.color).copy(alpha = 0.4f)
                    dark -> LAVENDER_TEXT
                    else -> Colors.BROWN.color.copy(alpha = 0.65f)
                }
                val background = when {
                    active && segment.enabled -> Colors.PRIMARY.color
                    active -> Colors.PRIMARY.color.copy(alpha = 0.45f)
                    else -> Color.Transparent
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .then(if (fill) Modifier.weight(1f) else Modifier)
                        .clip(ROW_SHAPE)
                        .background(background)
                        .then(
                            if (segment.enabled) {
                                Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Tab) { onSelect(index) }
                            } else {
                                Modifier
                            },
                        )
                        .semantics { this.selected = active }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    segment.icon?.let {
                        Image(painterResource(it), contentDescription = null, colorFilter = ColorFilter.tint(content), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        segment.label,
                        color = content,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /**
     * The preview line of the mockup: list or bracket view and the full screen view. The bracket view needs a drawn
     * elimination or round robin; the full screen view comes with the bout numbers, so until then it is disabled and says why.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PreviewNavigation(bracketAvailable: Boolean, bracketSelected: Boolean, onSelect: (Boolean) -> Unit) {
        val hint = Localization.getString(if (bracketAvailable) "brackets_fullscreen_next" else "brackets_bracket_after_draw")
        TooltipArea(
            tooltip = {
                Text(
                    hint,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clip(ROW_SHAPE).background(Colors.BROWN.color).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SegmentedControl(
                    segments = listOf(
                        Segment(Localization.getString("brackets_view_list"), Res.drawable.list_icon),
                        Segment(Localization.getString("brackets_view_bracket"), Res.drawable.bracket_icon, enabled = bracketAvailable),
                    ),
                    selectedIndex = if (bracketSelected) 1 else 0,
                    onSelect = { onSelect(it == 1) },
                    dark = false,
                    fill = false,
                )
                val content = Colors.PRIMARY.color.copy(alpha = 0.4f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(ROW_SHAPE)
                        .background(Color.White.copy(alpha = 0.5f))
                        .border(1.dp, content, ROW_SHAPE)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .semantics { contentDescription = Localization.getString("brackets_fullscreen") },
                ) {
                    Image(painterResource(Res.drawable.fullscreen_icon), contentDescription = null, colorFilter = ColorFilter.tint(content), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Localization.getString("brackets_fullscreen"), color = content, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }

    /** «Редактировать», or while editing the hint with «Сохранить» and «Назад без сохранения»; locked once started. */
    @Composable
    private fun EditBar(
        editing: Editing?,
        locked: Boolean,
        editable: Boolean,
        enabled: Boolean,
        onEdit: () -> Unit,
        onSave: () -> Unit,
        onBack: () -> Unit,
    ) {
        when {
            locked -> Text(
                Localization.getString("brackets_edit_locked"),
                color = Colors.BROWN.color.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
            editing == null -> ButtonComponent(
                text = Localization.getString("brackets_edit"),
                style = ButtonStyles.Secondary,
                onclick = onEdit,
                enabled = enabled && editable,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    Localization.getString("brackets_edit_hint"),
                    color = Colors.BROWN.color.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonComponent(
                        text = Localization.getString("brackets_edit_save"),
                        onclick = onSave,
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ButtonComponent(
                        text = Localization.getString("brackets_edit_back"),
                        style = ButtonStyles.Secondary,
                        onclick = onBack,
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    /** Competition name and date, the selected files and «Загрузить», which checks and imports in one step. */
    @Composable
    private fun ColumnScope.UploadTab(
        competitionName: String,
        onNameChange: (String) -> Unit,
        dateText: String,
        onDateChange: (String) -> Unit,
        files: List<File>,
        formReady: Boolean,
        busy: Boolean,
        onChooseFiles: () -> Unit,
        onRemoveFile: (File) -> Unit,
        onLoad: () -> Unit,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(CARD_SHAPE)
                .background(CARD_COLOR)
                .border(1.dp, CARD_BORDER, CARD_SHAPE)
                .padding(14.dp),
        ) {
            TextInputComponent(
                labelText = Localization.getString("brackets_competition_name"),
                inputValue = competitionName,
                onChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                validator = ::nameError,
                labelColor = LAVENDER_TEXT,
            )
            TextInputComponent(
                labelText = Localization.getString("brackets_competition_date"),
                inputValue = dateText,
                onChange = onDateChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = Localization.getString("brackets_date_placeholder"),
                validator = { if (parseDate(it) == null) Localization.getString("brackets_date_invalid") else null },
                labelColor = LAVENDER_TEXT,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                Localization.getString("brackets_files").replace("%d", files.size.toString()),
                color = LAVENDER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            if (files.isEmpty()) {
                Text(Localization.getString("brackets_files_empty"), color = MUTED_TEXT, style = MaterialTheme.typography.bodySmall)
            }
            ScrollableList(rememberLazyListState(), CARD_COLOR, Modifier.weight(1f)) { state ->
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(files, key = { _, file -> file.absolutePath }) { _, file -> FileRow(file, onRemove = { onRemoveFile(file) }) }
                }
            }
        }
        ActionBlock {
            ButtonComponent(
                text = Localization.getString("brackets_choose_files"),
                style = ButtonStyles.Secondary,
                onclick = onChooseFiles,
                enabled = formReady && !busy,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            // While the files are checked and written the button turns secondary instead of the grey disabled look.
            ButtonComponent(
                text = Localization.getString(if (busy) "brackets_uploading" else "brackets_import"),
                style = if (busy) ButtonStyles.Secondary else ButtonStyles.Primary,
                onclick = { if (!busy) onLoad() },
                enabled = busy || (formReady && files.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** Every import, newest first; a click shows its categories, the current one is marked. Below: the draw and the reset. */
    @Composable
    private fun ColumnScope.HistoryTab(
        history: ApplicationsHistory,
        viewedEventId: String?,
        onView: (ImportRecord) -> Unit,
        onRestore: (ImportRecord) -> Unit,
        onReset: () -> Unit,
        enabled: Boolean,
        drawBlock: @Composable () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(CARD_SHAPE)
                .background(CARD_COLOR)
                .border(1.dp, CARD_BORDER, CARD_SHAPE)
                .padding(12.dp),
        ) {
            Text(
                Localization.getString("brackets_history"),
                color = LAVENDER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            if (history.imports.isEmpty()) {
                Text(Localization.getString("brackets_history_empty"), color = MUTED_TEXT, style = MaterialTheme.typography.bodySmall)
            }
            ScrollableList(rememberLazyListState(), CARD_COLOR, Modifier.weight(1f)) { state ->
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    val newestFirst = history.imports.withIndex().reversed()
                    itemsIndexed(newestFirst, key = { _, indexed -> indexed.value.eventId }) { _, indexed ->
                        HistoryRow(
                            number = indexed.index + 1,
                            record = indexed.value,
                            history = history,
                            viewed = indexed.value.eventId == viewedEventId,
                            onView = { onView(indexed.value) },
                            onRestore = { onRestore(indexed.value) },
                            enabled = enabled,
                        )
                    }
                }
            }
            if (history.current != null) {
                Spacer(Modifier.height(8.dp))
                ButtonComponent(
                    text = Localization.getString("brackets_reset"),
                    onclick = onReset,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        }
        drawBlock()
    }

    @Composable
    private fun HistoryRow(
        number: Int,
        record: ImportRecord,
        history: ApplicationsHistory,
        viewed: Boolean,
        onView: () -> Unit,
        onRestore: () -> Unit,
        enabled: Boolean,
    ) {
        val applications = record.applications
        val current = record.eventId == history.currentEventId
        val content = if (viewed) Colors.PRIMARY.color else Color.White
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                // On the dark card the translucent secondary fill reads grey, so the opened import is plain white.
                .background(if (viewed) Color.White else Colors.PRIMARY.color)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClick = onView)
                .semantics { selected = viewed }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "№$number · ${IMPORTED_AT.format(record.importedAt)}",
                    color = content,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (current) Badge(Localization.getString("brackets_history_current"), inverted = viewed)
            }
            Text(
                "${applications.competitionName} · ${DATE_FORMAT.format(applications.competitionDate)}",
                color = content,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(summary("brackets_history_details", applications), color = content.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
            record.restoredFromEventId?.let { origin ->
                val originNumber = history.imports.indexOfFirst { it.eventId == origin } + 1
                Text(
                    Localization.getString("brackets_history_restored").replace("%d", originNumber.toString()),
                    color = content.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (viewed) {
                Spacer(Modifier.height(4.dp))
                applications.files.forEach { file ->
                    Text("• ${file.name}", color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!current) {
                    Spacer(Modifier.height(6.dp))
                    ButtonComponent(
                        text = Localization.getString("brackets_restore"),
                        onclick = onRestore,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    @Composable
    private fun Badge(text: String, inverted: Boolean) {
        Text(
            text,
            color = if (inverted) Color.White else Colors.PRIMARY.color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (inverted) Colors.PRIMARY.color else Colors.SECONDARY.color)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    /**
     * The draw card of the mockup (ADR-008 §2-4): the method of the competition with the separation criterion and the belt,
     * the system of the selected category, «Провести жеребьёвку», «Перемешать» and «Подтвердить». [board] is null while an
     * import from the history is open: the draw belongs to the current one.
     */
    @Composable
    private fun DrawBlock(
        board: DrawBoard?,
        selected: CategoryDrawState?,
        enabled: Boolean,
        onMethod: (DrawMethod) -> Unit,
        onSystem: (DrawSystem) -> Unit,
        onDrawAll: () -> Unit,
        onShuffle: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        val active = enabled && board != null && board.categories.isNotEmpty()
        val method = board?.method ?: DrawMethod()
        ActionBlock {
            Text(
                Localization.getString("brackets_draw"),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            DrawChoice(
                title = Localization.getString("brackets_draw_method"),
                optionKeys = listOf("brackets_draw_random", "brackets_draw_separation"),
                selectedIndex = if (method.criterion == null) 0 else 1,
                enabled = active,
                onSelect = { index -> onMethod(if (index == 0) DrawMethod() else DrawMethod(SeparationCriterion.COACH)) },
            )
            method.criterion?.let { criterion ->
                DrawChoice(
                    title = Localization.getString("brackets_draw_criterion"),
                    optionKeys = listOf("brackets_draw_region", "brackets_draw_city", "brackets_draw_organisation", "brackets_draw_coach"),
                    selectedIndex = criterion.ordinal,
                    enabled = active,
                    onSelect = { index -> onMethod(method.copy(criterion = SeparationCriterion.entries[index])) },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ROW_SHAPE)
                        .then(if (active) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Checkbox) { onMethod(method.copy(withBelt = !method.withBelt)) } else Modifier),
                ) {
                    Checkbox(
                        checked = method.withBelt,
                        onCheckedChange = null,
                        enabled = active,
                        colors = CheckboxDefaults.colors(checkedColor = Colors.PRIMARY.color, uncheckedColor = Colors.PRIMARY.color),
                    )
                    Text(Localization.getString("brackets_draw_belt"), color = Colors.BROWN.color, style = MaterialTheme.typography.bodySmall)
                }
            }
            DrawChoice(
                title = Localization.getString("brackets_draw_system_of"),
                optionKeys = listOf("brackets_draw_single_elimination", "brackets_draw_round_robin"),
                selectedIndex = (selected?.system ?: DrawSystem.SINGLE_ELIMINATION).ordinal,
                enabled = active && selected != null && selected.systemSelectable && selected.status != CategoryStatus.STARTED,
                onSelect = { index -> onSystem(DrawSystem.entries[index]) },
            )
            val drawable = board?.categories.orEmpty().any { it.status == CategoryStatus.NOT_DRAWN || it.status == CategoryStatus.NEEDS_REDRAW }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ButtonComponent(
                    text = Localization.getString("brackets_draw_all"),
                    onclick = onDrawAll,
                    enabled = active && drawable,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                ButtonComponent(
                    text = Localization.getString("brackets_draw_shuffle"),
                    style = ButtonStyles.Secondary,
                    onclick = onShuffle,
                    enabled = active && selected != null && selected.status != CategoryStatus.STARTED && selected.status != CategoryStatus.NO_BOUTS,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
            ButtonComponent(
                text = Localization.getString("brackets_draw_confirm").replace("%d", (board?.previews ?: 0).toString()),
                onclick = onConfirm,
                enabled = active && (board?.previews ?: 0) > 0,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            board?.let {
                val confirmed = it.categories.count { state ->
                    state.status == CategoryStatus.CONFIRMED || state.status == CategoryStatus.STARTED || state.status == CategoryStatus.NO_BOUTS
                }
                Text(
                    Localization.getString("brackets_draw_summary")
                        .replaceFirst("%d", confirmed.toString())
                        .replaceFirst("%d", it.categories.size.toString())
                        .replaceFirst("%d", it.previews.toString()),
                    color = Colors.PRIMARY.color,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    /** A titled choice of the draw as tabs. */
    @Composable
    private fun DrawChoice(title: String, optionKeys: List<String>, selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            SegmentedControl(
                segments = optionKeys.map { Segment(Localization.getString(it), enabled = enabled) },
                selectedIndex = selectedIndex,
                onSelect = { if (it != selectedIndex) onSelect(it) },
                dark = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** The light block of the devices screen: primary buttons stay readable when they are disabled. */
    @Composable
    private fun ActionBlock(content: @Composable ColumnScope.() -> Unit) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clip(CARD_SHAPE).background(Colors.SECONDARY.color).padding(10.dp),
            content = content,
        )
    }

    @Composable
    private fun FileRow(file: File, onRemove: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(Colors.PRIMARY.color).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                file.name,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val label = Localization.getString("brackets_remove_file")
            Image(
                painterResource(Res.drawable.cross_icon),
                contentDescription = label,
                modifier = Modifier
                    .size(14.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onRemove),
            )
        }
    }

    /**
     * A titled light card; when [empty], [emptyState] is centered in the whole card instead of the content. [toolbar] is a
     * line of controls under the title.
     */
    @Composable
    private fun ListCard(
        title: String,
        subtitle: String?,
        modifier: Modifier,
        empty: Boolean,
        emptyState: @Composable () -> Unit,
        toolbar: (@Composable () -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        Box(modifier = modifier.fillMaxSize().clip(LIST_SHAPE).background(Colors.SECONDARY.color)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Spacer(Modifier.height(14.dp))
                Text(title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (subtitle != null && !empty) {
                    Text(
                        subtitle,
                        color = Colors.BROWN.color.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                toolbar?.let {
                    Spacer(Modifier.height(10.dp))
                    it()
                }
                Spacer(Modifier.height(14.dp))
                AnimatedVisibility(visible = !empty, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
            AnimatedVisibility(
                visible = empty,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { emptyState() }
            }
        }
    }

    /** Illustrated empty card: what is missing and what to do about it, as on the devices screen. */
    @Composable
    private fun EmptyState(image: DrawableResource, titleKey: String, hintKey: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Image(painterResource(image), contentDescription = null, modifier = Modifier.size(140.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                Localization.getString(titleKey),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                Localization.getString(hintKey),
                color = Colors.PRIMARY.color.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    /**
     * A list with a visible scrollbar and a fade in [background] at each edge that still has content, so it is obvious that
     * it scrolls.
     */
    @Composable
    private fun ScrollableList(
        state: LazyListState,
        background: Color,
        modifier: Modifier = Modifier,
        content: @Composable (LazyListState) -> Unit,
    ) {
        Box(modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxSize().padding(end = 12.dp)) { content(state) }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(state),
                style = ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = 6.dp,
                    shape = RoundedCornerShape(3.dp),
                    hoverDurationMillis = 300,
                    unhoverColor = Colors.PRIMARY.color.copy(alpha = 0.45f),
                    hoverColor = Colors.PRIMARY.color,
                ),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
            AnimatedVisibility(
                visible = state.canScrollBackward,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .height(32.dp)
                        .background(Brush.verticalGradient(listOf(background, Color.Transparent))),
                )
            }
            AnimatedVisibility(
                visible = state.canScrollForward,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .height(32.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
                )
            }
        }
    }

    /**
     * Unselected categories are primary; the selected one has the secondary button style. Under the title: the draw status
     * (ADR-008 §11) and the clashes separation could not avoid.
     */
    @Composable
    private fun CategoryRow(category: ApplicationCategory, state: CategoryDrawState?, selected: Boolean, onClick: () -> Unit) {
        val content = if (selected) Colors.PRIMARY.color else Color.White
        val hint = Localization.getString("brackets_open_category")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(if (selected) SELECTED_ROW_COLOR else Colors.PRIMARY.color)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    contentDescription = category.title
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(category.title, color = content, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                state?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(it.status, inverted = selected)
                        if (it.clashes.isNotEmpty()) {
                            Text(
                                Localization.getString("brackets_clashes").replace("%d", it.clashes.size.toString()),
                                color = if (selected) FAILURE_COLOR else Colors.SECONDARY.color,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Image(
                painterResource(Res.drawable.bracket_icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(content),
                modifier = Modifier.size(20.dp),
            )
        }
    }

    /** The draw status as a badge; «требует новой жеребьёвки» stands out in the error colour. */
    @Composable
    private fun StatusBadge(status: CategoryStatus, inverted: Boolean) {
        if (status == CategoryStatus.NEEDS_REDRAW) {
            Text(
                statusLabel(status),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(FAILURE_COLOR).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else {
            Badge(statusLabel(status), inverted = inverted)
        }
    }

    /**
     * Entries in draw order: elimination by first-round pairs (a bye says the entry starts in round 2), round robin and
     * performance order numbered. While [editing], a click picks an entry and a second click swaps the two; the picked entry
     * and the last swapped pair are highlighted.
     */
    @Composable
    private fun DrawList(
        category: ApplicationCategory,
        draw: CategoryDraw,
        state: CategoryDrawState?,
        editing: Editing?,
        onPick: (String) -> Unit,
    ) {
        val byKey = category.entries.associateBy { it.drawKey }
        val clashing = state?.clashes.orEmpty().flatMap { listOf(it.first, it.second) }.toSet()
        val clashHint = Localization.getString(if (draw.format == DrawFormat.PERFORMANCE_ORDER) "brackets_clash_order" else "brackets_clash_bout")
        val highlighted = setOfNotNull(editing?.picked, editing?.swapped?.first, editing?.swapped?.second)

        @Composable
        fun EntryRow(number: String, key: String, note: String? = null) {
            val entry = byKey[key] ?: return
            val light = key in highlighted
            val textColor = if (light) Colors.PRIMARY.color else Color.White
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ROW_SHAPE)
                    .background(if (light) Color.White else Colors.PRIMARY.color)
                    .then(
                        if (editing != null) {
                            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onPick(key) }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { selected = light }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                entry.athletes.forEachIndexed { memberIndex, athlete ->
                    AthleteLines(if (memberIndex == 0) number else "", athlete, textColor)
                }
                note?.let { Text(it, color = if (light) Colors.PRIMARY.color else MUTED_LAVENDER, style = MaterialTheme.typography.labelSmall) }
                if (key in clashing) {
                    Text(clashHint, color = if (light) FAILURE_COLOR else Colors.SECONDARY.color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        ScrollableList(rememberLazyListState(), Colors.SECONDARY.color) { listState ->
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                when (draw.format) {
                    DrawFormat.ELIMINATION -> itemsIndexed(draw.positions.chunked(2)) { pairIndex, pair ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                Localization.getString("brackets_pair").replace("%d", (pairIndex + 1).toString()),
                                color = Colors.PRIMARY.color,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val bye = pair.count { it != null } == 1
                            pair.forEachIndexed { side, key ->
                                if (key != null) {
                                    EntryRow("${pairIndex * 2 + side + 1}.", key, Localization.getString("brackets_bye").takeIf { bye })
                                }
                            }
                        }
                    }
                    DrawFormat.NO_BOUTS -> itemsIndexed(draw.entryKeys) { _, key ->
                        EntryRow("1.", key, Localization.getString("brackets_no_bouts_place"))
                    }
                    DrawFormat.ROUND_ROBIN, DrawFormat.PERFORMANCE_ORDER -> itemsIndexed(draw.entryKeys) { index, key ->
                        EntryRow("${index + 1}.", key)
                    }
                }
            }
        }
    }

    /** Entries of one category in file order; pairs and teams list their members under one number. */
    @Composable
    private fun CategoryPreview(category: ApplicationCategory) {
        ScrollableList(rememberLazyListState(), Colors.SECONDARY.color) { state ->
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(category.entries, key = { _, entry -> entry.id }) { index, entry ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(Colors.PRIMARY.color).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        entry.athletes.forEachIndexed { memberIndex, athlete ->
                            AthleteLines(if (memberIndex == 0) "${index + 1}." else "", athlete, Color.White)
                        }
                        Text(
                            entry.sources.first().let { "${it.fileName} · ${it.sheet} · ${Localization.getString("brackets_row")} ${it.row}" },
                            color = MUTED_LAVENDER,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AthleteLines(number: String, athlete: Athlete, color: Color) {
        Row {
            Text(number, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Column {
                Text(athlete.fullName, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                    listOf(
                        DATE_FORMAT.format(athlete.birthDate),
                        athlete.sportQualification,
                        athlete.technicalQualification,
                        athlete.city,
                        athlete.region,
                        athlete.school,
                        athlete.coaches.distinct().joinToString(", "),
                    ).joinToString(" · "),
                    color = if (color == Color.White) Colors.SECONDARY.color else color.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    /** The validation report (`IMP-005`): where the error is, the value found and what the requirements expect. */
    @Composable
    private fun ErrorList(errors: List<ApplicationError>) {
        ScrollableList(rememberLazyListState(), Colors.SECONDARY.color) { state ->
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(errors) { index, error ->
                    val place = listOfNotNull(
                        error.file.ifEmpty { null },
                        error.sheet,
                        error.row?.let { "${Localization.getString("brackets_row")} $it" },
                        error.column,
                    ).joinToString(" · ")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ROW_SHAPE)
                            .background(FAILURE_COLOR)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "$place: ${error.reason}" },
                    ) {
                        Text(
                            "${index + 1}.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(32.dp),
                        )
                        Column {
                            if (place.isNotEmpty()) Text(place, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                            error.value?.let { Text("«$it»", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                            Text(error.reason, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    private fun statusLabel(status: CategoryStatus) = Localization.getString(
        when (status) {
            CategoryStatus.NOT_DRAWN -> "brackets_status_not_drawn"
            CategoryStatus.PREVIEW -> "brackets_status_preview"
            CategoryStatus.CONFIRMED -> "brackets_status_confirmed"
            CategoryStatus.NEEDS_REDRAW -> "brackets_status_needs_redraw"
            CategoryStatus.NO_BOUTS -> "brackets_status_no_bouts"
            CategoryStatus.STARTED -> "brackets_status_started"
        },
    )

    private fun rejection(reason: DrawRejection) = Localization.getString(
        when (reason) {
            DrawRejection.NO_COMPETITION -> "brackets_draw_rejected_no_competition"
            DrawRejection.UNKNOWN_CATEGORY -> "brackets_draw_rejected_unknown_category"
            DrawRejection.STARTED -> "brackets_edit_locked"
            DrawRejection.NOTHING_TO_CONFIRM -> "brackets_draw_rejected_nothing_to_confirm"
            DrawRejection.NOT_CONFIRMED -> "brackets_draw_rejected_not_confirmed"
            DrawRejection.UNKNOWN_ENTRY -> "brackets_draw_rejected_unknown_entry"
        },
    )

    private fun recordLabel(history: ApplicationsHistory, record: ImportRecord): String {
        val number = history.imports.indexOf(record) + 1
        val key = if (record.eventId == history.currentEventId) "brackets_viewing_current" else "brackets_viewing_history"
        return Localization.getString(key).replace("%d", number.toString())
    }

    private fun summary(key: String, applications: CompetitionApplications) = Localization.getString(key)
        .replaceFirst("%s", applications.athleteCount.toString())
        .replaceFirst("%s", applications.categories.size.toString())
        .replaceFirst("%s", applications.files.size.toString())

    private fun nameError(name: String): String? =
        if (name.isBlank()) Localization.getString("brackets_name_required") else null

    /** The system file dialog with several XLSX files selectable; null when the operator cancels. */
    private fun chooseFiles(): List<File>? {
        val dialog = FileDialog(null as Frame?, Localization.getString("brackets_choose_files"), FileDialog.LOAD).apply {
            isMultipleMode = true
            setFilenameFilter { _, name -> name.endsWith(".xlsx", ignoreCase = true) }
            isVisible = true
        }
        return dialog.files.toList().takeIf { it.isNotEmpty() }
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim(), DATE_FORMAT)
    } catch (_: DateTimeParseException) {
        null
    }

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    // The desktop JVM runs with `user.timezone=UTC`, so the import time is labelled as UTC instead of passing for local time.
    private val IMPORTED_AT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
    private val LIST_SHAPE = RoundedCornerShape(15.dp)
    private val CARD_SHAPE = RoundedCornerShape(16.dp)
    private val ROW_SHAPE = RoundedCornerShape(8.dp)
    // The secondary button's fill: a selected category reads like `ButtonStyles.Secondary`, text in primary.
    private val SELECTED_ROW_COLOR = Color.White.copy(alpha = 0.48f)
    private val FAILURE_COLOR = Color(0xFFB3261E)
    private val CARD_COLOR = Color(0xFF27262D)
    private val CARD_BORDER = Color(0xFF34333B)
    private val LAVENDER_TEXT = Color(0xFFC9B6F2)
    private val MUTED_LAVENDER = Color(0xFFD9C7FF)
    private val MUTED_TEXT = Color(0xFF9E9AA7)
}
