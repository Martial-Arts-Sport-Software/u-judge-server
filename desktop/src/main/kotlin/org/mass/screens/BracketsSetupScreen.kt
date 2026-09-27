package org.mass.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.mass.Server
import org.mass.applications.ApplicationCategory
import org.mass.applications.ApplicationError
import org.mass.applications.ApplicationFileInput
import org.mass.applications.Athlete
import org.mass.applications.CompetitionApplications
import org.mass.applications.ImportOutcome
import org.mass.applications.ImportPreparation
import org.mass.applications.ImportedCompetition
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.screen_header.ScreenHeaderComponent
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.cross_icon
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * «Настройка турнирных сеток», step one (I4a, ADR-005): the operator selects the application files of all coaches, checks
 * them against «Требования к заполнению заявок» and imports the competition. Every error blocks the import and is listed
 * with its cell; a valid selection shows its categories and athletes before anything is written.
 */
object BracketsSetupScreen : Screen {
    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val service = remember(runtimeState) { Server.runtime.competitionApplications }
        val current by (service?.current ?: remember { MutableStateFlow<ImportedCompetition?>(null) }).collectAsState()
        val scope = rememberCoroutineScope()

        var competitionName by remember { mutableStateOf("") }
        var dateText by remember { mutableStateOf("") }
        var files by remember { mutableStateOf(listOf<File>()) }
        var preparation by remember { mutableStateOf<ImportPreparation?>(null) }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<Message?>(null) }
        var replaceCandidate by remember { mutableStateOf<ImportedCompetition?>(null) }

        LaunchedEffect(current?.eventId) {
            current?.applications?.let { imported ->
                if (competitionName.isBlank()) competitionName = imported.competitionName
                if (dateText.isBlank()) dateText = DATE_FORMAT.format(imported.competitionDate)
            }
        }

        fun resetCheck() {
            preparation = null
            message = null
        }

        fun check() {
            val date = parseDate(dateText)
            if (service == null || date == null) {
                message = Message(Localization.getString(if (service == null) "brackets_server_unavailable" else "brackets_date_invalid"), error = true)
                return
            }
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        service.prepare(competitionName, date, files.map { ApplicationFileInput(it.name, it.readBytes()) })
                    }
                }
                busy = false
                result.onSuccess { checked ->
                    preparation = checked
                    message = when (checked) {
                        is ImportPreparation.Invalid -> Message(Localization.getString("brackets_check_failed").replace("%d", checked.errors.size.toString()), error = true)
                        is ImportPreparation.Valid -> Message(summary("brackets_check_passed", checked.applications), error = false)
                    }
                    selectedCategoryId = (checked as? ImportPreparation.Valid)?.applications?.categories?.firstOrNull()?.id
                }.onFailure { message = Message(it.message ?: it::class.simpleName.orEmpty(), error = true) }
            }
        }

        fun import(applications: CompetitionApplications, replaceConfirmed: Boolean) {
            val importService = service ?: return
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { importService.import(applications, replaceConfirmed) }
                busy = false
                when (outcome) {
                    is ImportOutcome.Imported -> {
                        preparation = null
                        message = Message(
                            summary("brackets_imported", outcome.competition.applications) +
                                (outcome.backup?.let { "\n" + Localization.getString("brackets_backup").replace("%s", it.toString()) } ?: ""),
                            error = false,
                        )
                    }
                    ImportOutcome.AlreadyImported -> {
                        preparation = null
                        message = Message(Localization.getString("brackets_already_imported"), error = false)
                    }
                    is ImportOutcome.ReplaceNeedsConfirmation -> replaceCandidate = outcome.current
                    is ImportOutcome.Failed -> message = Message(outcome.diagnostic, error = true)
                }
            }
        }

        val valid = (preparation as? ImportPreparation.Valid)?.applications
        val errors = (preparation as? ImportPreparation.Invalid)?.errors
        val shown = valid ?: current?.applications
        val selected = shown?.categories?.firstOrNull { it.id == selectedCategoryId } ?: shown?.categories?.firstOrNull()

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(0.3f)
                        .fillMaxHeight()
                        .clip(PANEL_SHAPE)
                        .background(Colors.GRAY.color)
                        .padding(20.dp),
                ) {
                    ApplicationsCard(
                        competitionName = competitionName,
                        onNameChange = { competitionName = it; resetCheck() },
                        dateText = dateText,
                        onDateChange = { dateText = it; resetCheck() },
                        files = files,
                        onChooseFiles = {
                            chooseFiles()?.let { chosen ->
                                files = (files + chosen).distinctBy { it.absolutePath }
                                resetCheck()
                            }
                        },
                        onRemoveFile = { file -> files = files - file; resetCheck() },
                        canCheck = !busy && service != null && files.isNotEmpty(),
                        onCheck = ::check,
                        canImport = !busy && valid != null,
                        onImport = { valid?.let { import(it, replaceConfirmed = false) } },
                    )
                    message?.let { MessagePill(it) }
                    Spacer(Modifier.weight(1f))
                    current?.let { CurrentImportCard(it) }
                    DrawPlaceholder()
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
                        title = Localization.getString(if (valid != null) "brackets_categories_checked" else "brackets_categories"),
                        modifier = Modifier.weight(0.45f),
                        emptyText = Localization.getString("brackets_categories_empty").takeIf { shown == null || shown.categories.isEmpty() },
                    ) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(shown?.categories.orEmpty(), key = { _, category -> category.id }) { _, category ->
                                CategoryRow(category, selected = category.id == selected?.id, onClick = { selectedCategoryId = category.id })
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    ListCard(
                        title = Localization.getString(if (errors != null) "brackets_errors" else "brackets_preview"),
                        modifier = Modifier.weight(0.55f),
                        emptyText = Localization.getString("brackets_preview_empty").takeIf { errors == null && selected == null },
                    ) {
                        if (errors != null) ErrorList(errors) else selected?.let { CategoryPreview(it) }
                    }
                }
            }
        }

        replaceCandidate?.let { existing ->
            AlertDialog(
                onDismissRequest = { replaceCandidate = null },
                title = { Text(Localization.getString("brackets_replace_title")) },
                text = {
                    Text(
                        Localization.getString("brackets_replace_text")
                            .replaceFirst("%s", existing.applications.competitionName)
                            .replaceFirst("%s", existing.applications.athleteCount.toString()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        replaceCandidate = null
                        valid?.let { import(it, replaceConfirmed = true) }
                    }) { Text(Localization.getString("brackets_replace")) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        replaceCandidate = null
                        message = Message(Localization.getString("brackets_replace_cancelled"), error = false)
                    }) { Text(Localization.getString("devices_cancel")) }
                },
            )
        }
    }

    /** Competition name and date, the selected files and the two steps: check, then import. */
    @Composable
    private fun ApplicationsCard(
        competitionName: String,
        onNameChange: (String) -> Unit,
        dateText: String,
        onDateChange: (String) -> Unit,
        files: List<File>,
        onChooseFiles: () -> Unit,
        onRemoveFile: (File) -> Unit,
        canCheck: Boolean,
        onCheck: () -> Unit,
        canImport: Boolean,
        onImport: () -> Unit,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CARD_SHAPE)
                .background(CARD_COLOR)
                .border(1.dp, CARD_BORDER, CARD_SHAPE)
                .padding(14.dp),
        ) {
            Text(
                Localization.getString("brackets_applications_title"),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Field(Localization.getString("brackets_competition_name"), competitionName, onNameChange)
            Field(Localization.getString("brackets_competition_date"), dateText, onDateChange, placeholder = Localization.getString("brackets_date_placeholder"))
            Text(
                Localization.getString("brackets_files").replace("%d", files.size.toString()),
                color = LAVENDER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(files, key = { _, file -> file.absolutePath }) { _, file -> FileRow(file, onRemove = { onRemoveFile(file) }) }
                }
            }
            ButtonComponent(
                text = Localization.getString("brackets_choose_files"),
                style = ButtonStyles.Secondary,
                onclick = onChooseFiles,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        // The two steps sit on the light block, like the confirm block of the devices screen, so a disabled step stays visible.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().clip(CARD_SHAPE).background(Colors.SECONDARY.color).padding(12.dp),
        ) {
            ButtonComponent(
                text = Localization.getString("brackets_check"),
                onclick = onCheck,
                enabled = canCheck,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            ButtonComponent(
                text = Localization.getString("brackets_import"),
                onclick = onImport,
                enabled = canImport,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String? = null) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, color = LAVENDER_TEXT, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ROW_SHAPE)
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    cursorBrush = SolidColor(Colors.PRIMARY.color),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
        }
    }

    @Composable
    private fun FileRow(file: File, onRemove: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(ROW_COLOR).padding(horizontal = 10.dp, vertical = 6.dp),
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
                modifier = Modifier.size(14.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onRemove),
            )
        }
    }

    @Composable
    private fun MessagePill(message: Message) {
        Text(
            text = message.text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(if (message.error) FAILURE_COLOR else ROW_COLOR)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = message.text },
        )
    }

    /** What the journal holds now: the latest import and how many imports its history keeps. */
    @Composable
    private fun CurrentImportCard(current: ImportedCompetition) {
        val applications = current.applications
        Column(
            modifier = Modifier.fillMaxWidth().clip(CARD_SHAPE).background(CARD_COLOR).padding(12.dp),
        ) {
            Text(Localization.getString("brackets_current"), color = LAVENDER_TEXT, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(
                "${applications.competitionName} · ${DATE_FORMAT.format(applications.competitionDate)}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                Localization.getString("brackets_current_details")
                    .replaceFirst("%s", applications.athleteCount.toString())
                    .replaceFirst("%s", applications.categories.size.toString())
                    .replaceFirst("%s", applications.files.size.toString())
                    .replaceFirst("%s", IMPORTED_AT.format(current.importedAt))
                    .replaceFirst("%s", current.importCount.toString()),
                color = MUTED_TEXT,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** The draw card of the mockup; the draw by FHR 2024 §1.4 arrives with I4b (ADR-007). */
    @Composable
    private fun DrawPlaceholder() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clip(CARD_SHAPE).background(Colors.SECONDARY.color).padding(12.dp),
        ) {
            ButtonComponent(
                text = Localization.getString("brackets_draw"),
                onclick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                Localization.getString("brackets_draw_next"),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun ListCard(title: String, modifier: Modifier, emptyText: String?, content: @Composable () -> Unit) {
        Box(modifier = modifier.fillMaxSize().clip(LIST_SHAPE).background(Colors.SECONDARY.color)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                AnimatedVisibility(visible = emptyText == null, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
            AnimatedVisibility(
                visible = emptyText != null,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        emptyText.orEmpty(),
                        color = Colors.PRIMARY.color,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun CategoryRow(category: ApplicationCategory, selected: Boolean, onClick: () -> Unit) {
        val count = Localization.getString("brackets_entries").replace("%d", category.entries.size.toString())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(if (selected) ROW_COLOR else ROW_COLOR.copy(alpha = 0.55f))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = "${category.title}, $count" },
        ) {
            Text(category.title, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(count, color = Colors.SECONDARY.color, style = MaterialTheme.typography.bodySmall)
        }
    }

    /** Entries of one category in file order; pairs and teams list their members under one number. */
    @Composable
    private fun CategoryPreview(category: ApplicationCategory) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(category.title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(category.entries, key = { _, entry -> entry.id }) { index, entry ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(ROW_COLOR).padding(horizontal = 12.dp, vertical = 8.dp),
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(errors) { _, error ->
                val place = listOfNotNull(
                    error.file.ifEmpty { null },
                    error.sheet,
                    error.row?.let { "${Localization.getString("brackets_row")} $it" },
                    error.column,
                ).joinToString(" · ")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ROW_SHAPE)
                        .background(FAILURE_COLOR)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "$place: ${error.reason}" },
                ) {
                    if (place.isNotEmpty()) Text(place, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    error.value?.let { Text("«$it»", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                    Text(error.reason, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun summary(key: String, applications: CompetitionApplications) = Localization.getString(key)
        .replaceFirst("%s", applications.athleteCount.toString())
        .replaceFirst("%s", applications.categories.size.toString())
        .replaceFirst("%s", applications.files.size.toString())

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

    private data class Message(val text: String, val error: Boolean)

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    // The desktop JVM runs with `user.timezone=UTC`, so the import time is labelled as UTC instead of passing for local time.
    private val IMPORTED_AT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
    private val LIST_SHAPE = RoundedCornerShape(15.dp)
    private val CARD_SHAPE = RoundedCornerShape(16.dp)
    private val ROW_SHAPE = RoundedCornerShape(8.dp)
    private val ROW_COLOR = Color(0xFF6A2BDD)
    private val FAILURE_COLOR = Color(0xFFB3261E)
    private val CARD_COLOR = Color(0xFF27262D)
    private val CARD_BORDER = Color(0xFF34333B)
    private val LAVENDER_TEXT = Color(0xFFC9B6F2)
    private val MUTED_LAVENDER = Color(0xFFD9C7FF)
    private val MUTED_TEXT = Color(0xFF9E9AA7)
}
