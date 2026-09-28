package org.mass.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
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
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
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
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * «Настройка турнирных сеток» (I4a, ADR-005). The upload tab takes the competition name and date and several application
 * files; «Загрузить» checks them against «Требования к заполнению заявок» and imports them in one step. The history tab
 * lists every import, lets the operator view or restore one, reset them all, and holds the draw of I4b.
 */
object BracketsSetupScreen : Screen {
    private enum class Tab(val key: String) { UPLOAD("brackets_tab_upload"), HISTORY("brackets_tab_history") }

    /** A confirmation the operator must give before the journal changes. */
    private sealed interface Confirmation {
        data class Replace(val applications: CompetitionApplications, val current: ImportRecord) : Confirmation
        data class Restore(val record: ImportRecord, val number: Int) : Confirmation
        data object Reset : Confirmation
    }

    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val service = remember(runtimeState) { Server.runtime.competitionApplications }
        val history by (service?.history ?: remember { MutableStateFlow(ApplicationsHistory(emptyList(), null)) }).collectAsState()
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
                }
            }
        }

        val viewed = history.imports.firstOrNull { it.eventId == viewedEventId } ?: history.current
        val shown = viewed?.applications
        val selected = shown?.categories?.firstOrNull { it.id == selectedCategoryId } ?: shown?.categories?.firstOrNull()

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .clip(PANEL_SHAPE)
                            .background(Colors.GRAY.color)
                            .padding(16.dp),
                    ) {
                        TabSwitch(tab, onSelect = { tab = it })
                        when (tab) {
                            Tab.UPLOAD -> UploadTab(
                                competitionName = competitionName,
                                onNameChange = { competitionName = it },
                                dateText = dateText,
                                onDateChange = { dateText = it },
                                files = files,
                                formReady = formReady && !busy && service != null,
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
                            ScrollableList(rememberLazyListState()) { state ->
                                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(shown?.categories.orEmpty(), key = { _, category -> category.id }) { _, category ->
                                        CategoryRow(category, selected = category.id == selected?.id, onClick = { selectedCategoryId = category.id })
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
                        ) {
                            if (errorList != null) ErrorList(errorList) else selected?.let { CategoryPreview(it) }
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
            null -> Unit
        }
    }

    @Composable
    private fun TabSwitch(selected: Tab, onSelect: (Tab) -> Unit) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(CARD_COLOR).padding(4.dp),
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                Text(
                    Localization.getString(tab.key),
                    color = if (active) Color.White else LAVENDER_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(ROW_SHAPE)
                        .background(if (active) Colors.PRIMARY.color else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = active }
                        .padding(vertical = 8.dp),
                )
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
            ScrollableList(rememberLazyListState(), Modifier.weight(1f)) { state ->
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(files, key = { _, file -> file.absolutePath }) { _, file -> FileRow(file, onRemove = { onRemoveFile(file) }) }
                }
            }
        }
        ActionBlock {
            ButtonComponent(
                text = Localization.getString("brackets_choose_files"),
                onclick = onChooseFiles,
                enabled = formReady,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            ButtonComponent(
                text = Localization.getString("brackets_import"),
                onclick = onLoad,
                enabled = formReady && files.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            if (!formReady) {
                Text(
                    Localization.getString("brackets_fill_form"),
                    color = Colors.PRIMARY.color,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
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
            ScrollableList(rememberLazyListState(), Modifier.weight(1f)) { state ->
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
                    style = ButtonStyles.Secondary,
                    onclick = onReset,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DrawBlock()
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
                .background(if (viewed) SELECTED_ROW_COLOR else Colors.PRIMARY.color)
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
     * The draw card of the mockup: system, method and «Перемешать». The draw by FHR 2024 §1.2 and §1.4 (ADR-007) arrives
     * with I4b, so the controls are shown but disabled.
     */
    @Composable
    private fun DrawBlock() {
        ActionBlock {
            Text(
                Localization.getString("brackets_draw"),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            RadioGroup(Localization.getString("brackets_draw_system"), listOf("brackets_draw_single_elimination", "brackets_draw_round_robin"))
            RadioGroup(Localization.getString("brackets_draw_method"), listOf("brackets_draw_random", "brackets_draw_separation"))
            ButtonComponent(
                text = Localization.getString("brackets_draw_shuffle"),
                onclick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Text(
                Localization.getString("brackets_draw_next"),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun RadioGroup(title: String, optionKeys: List<String>) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                optionKeys.forEachIndexed { index, key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(50))
                                .border(2.dp, Colors.PRIMARY.color.copy(alpha = 0.45f), RoundedCornerShape(50)),
                        ) {
                            if (index == 0) Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Colors.PRIMARY.color.copy(alpha = 0.45f)))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(Localization.getString(key), color = Colors.BROWN.color.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    /** The light block of the devices screen: primary buttons stay readable when they are disabled. */
    @Composable
    private fun ActionBlock(content: @Composable ColumnScope.() -> Unit) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clip(CARD_SHAPE).background(Colors.SECONDARY.color).padding(12.dp),
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

    /** A titled light card; when [empty], [emptyState] is centered in the whole card instead of the content. */
    @Composable
    private fun ListCard(
        title: String,
        subtitle: String?,
        modifier: Modifier,
        empty: Boolean,
        emptyState: @Composable () -> Unit,
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

    /** A list with a visible scrollbar and a fade at the edge that still has content, so it is obvious that it scrolls. */
    @Composable
    private fun ScrollableList(state: LazyListState, modifier: Modifier = Modifier, content: @Composable (LazyListState) -> Unit) {
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
            if (state.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .height(36.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, FADE_COLOR))),
                )
            }
        }
    }

    /** Unselected categories are primary; the selected one is light with a primary outline and text. */
    @Composable
    private fun CategoryRow(category: ApplicationCategory, selected: Boolean, onClick: () -> Unit) {
        val content = if (selected) Colors.PRIMARY.color else Color.White
        val hint = Localization.getString("brackets_open_category")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(if (selected) SELECTED_ROW_COLOR else Colors.PRIMARY.color)
                .border(2.dp, if (selected) Colors.PRIMARY.color else Color.Transparent, ROW_SHAPE)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    contentDescription = category.title
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(category.title, color = content, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Image(
                painterResource(Res.drawable.bracket_icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(content),
                modifier = Modifier.size(20.dp),
            )
        }
    }

    /** Entries of one category in file order; pairs and teams list their members under one number. */
    @Composable
    private fun CategoryPreview(category: ApplicationCategory) {
        ScrollableList(rememberLazyListState()) { state ->
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(category.entries, key = { _, entry -> entry.id }) { index, entry ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(Colors.PRIMARY.color).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        entry.athletes.forEachIndexed { memberIndex, athlete ->
                            AthleteLines(if (memberIndex == 0) "${index + 1}." else "", athlete)
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
    private fun AthleteLines(number: String, athlete: Athlete) {
        Row {
            Text(number, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Column {
                Text(athlete.fullName, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                    color = Colors.SECONDARY.color,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    /** The validation report (`IMP-005`): where the error is, the value found and what the requirements expect. */
    @Composable
    private fun ErrorList(errors: List<ApplicationError>) {
        ScrollableList(rememberLazyListState()) { state ->
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
    private val SELECTED_ROW_COLOR = Color.White.copy(alpha = 0.7f)
    private val FAILURE_COLOR = Color(0xFFB3261E)
    private val CARD_COLOR = Color(0xFF27262D)
    private val CARD_BORDER = Color(0xFF34333B)
    private val LAVENDER_TEXT = Color(0xFFC9B6F2)
    private val MUTED_LAVENDER = Color(0xFFD9C7FF)
    private val MUTED_TEXT = Color(0xFF9E9AA7)
    private val FADE_COLOR = Color(0xFFEFD4FF)
}
