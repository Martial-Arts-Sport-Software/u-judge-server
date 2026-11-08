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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mass.RestoreOutcome
import org.mass.Server
import org.mass.draw.DrawOutcome
import org.mass.draw.ProtocolImages
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.persistence.BackupEntry
import org.mass.persistence.BackupInspection
import org.mass.persistence.BackupStatus
import org.mass.persistence.JournalBackup
import org.mass.persistence.JournalBackups
import org.mass.protocol.ProtocolKind
import org.mass.protocol.ProtocolPdf
import org.mass.report.CompetitionReport
import org.mass.report.HistoryFilter
import org.mass.report.HistoryRow
import org.mass.report.HistoryStatus
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.dialog.ConfirmDialogComponent
import org.mass.ui.report.HistoryText
import org.mass.ui.report.ReportFiles
import org.mass.ui.screen_header.ScreenHeaderComponent
import org.mass.ui.toast.Toast
import org.mass.ui.toast.ToastComponent
import java.io.File
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «Отчёты и резервные копии» (I6, ADR-012). The left column saves XLSX, CSV and the protocols to a chosen path and sets the
 * seal and signature images; the middle one is the history of every session input with filters by court, category, bout,
 * judge and action (`AUD-003`), each row saying why it counted or not; the right one lists the backups, makes one on
 * demand, copies, deletes and restores one after a confirmation (`BAK-002`-`BAK-004`).
 */
object ReportsScreen : Screen {
    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val reports = remember(runtimeState) { Server.runtime.reports }
        val backups = remember(runtimeState) { Server.runtime.backups }
        val courts = remember(runtimeState) { Server.runtime.competitionCourts }
        val hub = remember(runtimeState) { Server.runtime.realtime }
        val courtsRevision by (courts?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val boutsRevision by (hub?.bouts?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val performancesRevision by (hub?.performances?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
        val backupStatus by (backups?.status ?: remember { MutableStateFlow(BackupStatus()) }).collectAsState()
        val scope = rememberCoroutineScope()

        var report by remember { mutableStateOf<CompetitionReport?>(null) }
        var backupList by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
        var filter by remember { mutableStateOf(HistoryFilter()) }
        var toast by remember { mutableStateOf<Toast?>(null) }
        var busy by remember { mutableStateOf(false) }
        var selectedBackup by remember { mutableStateOf<Path?>(null) }
        var pendingRestore by remember { mutableStateOf<Pair<Path, BackupInspection.Valid>?>(null) }
        var pendingDelete by remember { mutableStateOf<Path?>(null) }

        LaunchedEffect(reports, courtsRevision, boutsRevision, performancesRevision) {
            report = withContext(Dispatchers.IO) { runCatching { reports?.report() }.getOrNull() }
        }
        LaunchedEffect(backups, backupStatus) {
            backupList = withContext(Dispatchers.IO) { runCatching { backups?.list().orEmpty() }.getOrDefault(emptyList()) }
        }

        /** Runs [action] off the UI thread; a click while one runs is ignored, a failure is shown (`UI-007`). */
        fun run(action: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    action()
                } catch (exception: Exception) {
                    toast = Toast(Localization.getString("reports_failed").replace("%s", exception.message ?: exception::class.simpleName.orEmpty()), error = true)
                } finally {
                    busy = false
                }
            }
        }

        fun refreshBackups() = scope.launch {
            backupList = withContext(Dispatchers.IO) { backups?.list().orEmpty() }
        }

        fun saveReport(extension: String, render: () -> ByteArray?) {
            val current = report ?: return
            val file = ReportFiles.save("${current.competition.name} ${current.competition.date}", extension) ?: return
            run {
                val bytes = withContext(Dispatchers.IO) { render() } ?: return@run
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                toast = Toast(Localization.getString("reports_saved").replace("%s", file.absolutePath))
            }
        }

        fun saveProtocol(kind: ProtocolKind) {
            val service = reports ?: return
            val title = Localization.getString(if (kind == ProtocolKind.RESULTS) "reports_protocol_results" else "reports_protocol_progress")
            val file = ReportFiles.save(title, "pdf") ?: return
            run {
                val document = withContext(Dispatchers.IO) { service.protocol(kind) }
                if (document == null) {
                    toast = Toast(Localization.getString("courts_download_none"), error = true)
                    return@run
                }
                withContext(Dispatchers.IO) { file.writeBytes(ProtocolPdf.render(document)) }
                toast = Toast(Localization.getString("reports_saved").replace("%s", file.absolutePath))
            }
        }

        fun setImage(change: (ProtocolImages, String?) -> ProtocolImages, choose: Boolean) {
            val service = courts ?: return
            val file = if (choose) ReportFiles.open("reports_image_open") ?: return else null
            run {
                val outcome = withContext(Dispatchers.IO) {
                    val encoded = file?.let { picked -> picked.readBytes().takeIf { it.size <= ProtocolImages.MAX_BYTES }?.let(ProtocolImages::encode) ?: "" }
                    service.setProtocolImages(change(service.board().images, encoded))
                }
                toast = when (outcome) {
                    DrawOutcome.Done -> Toast(Localization.getString("reports_image_saved"))
                    is DrawOutcome.Rejected -> Toast(Localization.getString("reports_image_invalid"), error = true)
                    is DrawOutcome.Failed -> Toast(outcome.diagnostic, error = true)
                }
            }
        }

        fun inspectAndConfirm(path: Path) = run {
            when (val inspection = withContext(Dispatchers.IO) { JournalBackup.inspect(path) }) {
                is BackupInspection.Invalid -> toast = Toast(rejection(inspection.code), error = true)
                is BackupInspection.Valid -> pendingRestore = path to inspection
            }
        }

        val active = reports != null && report != null
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                    Panel(Modifier.weight(0.24f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Card {
                                CardTitle(Localization.getString("reports_exports"))
                                if (!active) Hint(Localization.getString("reports_empty"))
                                ActionButton("reports_save_xlsx", active && !busy) { saveReport("xlsx") { reports?.xlsx() } }
                                ActionButton("reports_save_csv", active && !busy) { saveReport("csv") { reports?.csv() } }
                                Hint(Localization.getString("reports_csv_hint"))
                                ActionButton("reports_protocol_progress", active && !busy) { saveProtocol(ProtocolKind.PROGRESS) }
                                ActionButton("reports_protocol_results", active && !busy) { saveProtocol(ProtocolKind.RESULTS) }
                            }
                            val images = remember(courtsRevision, courts) { courts?.board()?.images ?: ProtocolImages() }
                            Card {
                                CardTitle(Localization.getString("reports_images"))
                                Hint(Localization.getString("reports_images_hint"))
                                listOf<Triple<String, String?, (ProtocolImages, String?) -> ProtocolImages>>(
                                    Triple("reports_image_seal", images.seal) { current, value -> current.copy(seal = value) },
                                    Triple("reports_image_chief_judge", images.chiefJudgeSignature) { current, value -> current.copy(chiefJudgeSignature = value) },
                                    Triple("reports_image_chief_secretary", images.chiefSecretarySignature) { current, value -> current.copy(chiefSecretarySignature = value) },
                                ).forEach { (key, value, change) ->
                                    Text(
                                        "${Localization.getString(key)}: ${Localization.getString(if (value == null) "reports_image_none" else "reports_image_set")}",
                                        color = Colors.BROWN.color,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ActionButton("reports_image_choose", active && !busy, Modifier.weight(1f)) { setImage(change, choose = true) }
                                        ActionButton("reports_image_remove", active && !busy && value != null, Modifier.weight(1f), ButtonStyles.Plain) {
                                            setImage(change, choose = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Panel(Modifier.weight(0.48f)) {
                        Card(Modifier.fillMaxSize()) {
                            CardTitle(Localization.getString("reports_history"))
                            val current = report
                            if (current == null) {
                                Hint(Localization.getString("reports_empty"))
                            } else {
                                Filters(current, filter) { filter = it }
                                val rows = remember(current, filter) { current.history(filter).asReversed() }
                                Hint(Localization.getString("reports_history_count").replace("%d", rows.size.toString()))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    items(rows, key = HistoryRow::eventId) { row -> HistoryLine(row, current.competition.zone) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Panel(Modifier.weight(0.28f)) {
                        Card(Modifier.fillMaxSize()) {
                            CardTitle(Localization.getString("reports_backups"))
                            Hint(Localization.getString("reports_backups_hint"))
                            backupStatus.failure?.let { Text(Localization.getString("reports_backup_failed").replace("%s", it), color = Colors.RED.color, style = MaterialTheme.typography.labelSmall) }
                            backupStatus.lastAt?.let { Hint(Localization.getString("reports_backup_last").replace("%s", LOCAL.format(it.atZone(ZoneId.systemDefault())))) }
                            ActionButton("reports_backup_create", backups != null && !busy) {
                                run {
                                    val path = withContext(Dispatchers.IO) { checkNotNull(backups).create(JournalBackups.MANUAL) }
                                    toast = Toast(Localization.getString("reports_saved").replace("%s", path.toString()))
                                    refreshBackups()
                                }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                                items(backupList, key = { it.path.toString() }) { entry ->
                                    BackupLine(entry, selected = entry.path == selectedBackup) {
                                        selectedBackup = entry.path.takeIf { it != selectedBackup }
                                    }
                                }
                            }
                            val selected = selectedBackup?.takeIf { path -> backupList.any { it.path == path } }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ActionButton("reports_backup_copy", selected != null && !busy, Modifier.weight(1f), ButtonStyles.Plain) {
                                    val source = selected ?: return@ActionButton
                                    val target = ReportFiles.save(source.fileName.toString().removeSuffix(".jsonl"), "jsonl") ?: return@ActionButton
                                    run {
                                        withContext(Dispatchers.IO) { checkNotNull(backups).copy(source, target.toPath()) }
                                        toast = Toast(Localization.getString("reports_saved").replace("%s", target.absolutePath))
                                    }
                                }
                                ActionButton("reports_backup_delete", selected != null && !busy, Modifier.weight(1f), ButtonStyles.Plain) {
                                    pendingDelete = selected
                                }
                            }
                            ActionButton("reports_backup_restore", selected != null && !busy) { selected?.let(::inspectAndConfirm) }
                            ActionButton("reports_backup_restore_file", backups != null && !busy, style = ButtonStyles.Plain) {
                                ReportFiles.open("reports_backup_open")?.let(File::toPath)?.let(::inspectAndConfirm)
                            }
                        }
                    }
                }
                ToastComponent(toast, onDismiss = { toast = null }, modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        pendingRestore?.let { (path, inspection) ->
            val header = inspection.backup.header
            ConfirmDialogComponent(
                title = Localization.getString("reports_restore_title"),
                text = Localization.getString("reports_restore_text")
                    .replaceFirst("%s", path.fileName.toString())
                    .replaceFirst("%s", header.createdAt)
                    .replaceFirst("%s", header.events.toString()),
                confirmText = Localization.getString("reports_restore_confirm"),
                cancelText = Localization.getString("bout_back"),
                onConfirm = {
                    pendingRestore = null
                    run {
                        toast = Toast(Localization.getString("reports_restore_running"))
                        toast = when (val outcome = withContext(Dispatchers.IO) { Server.runtime.restore(path) }) {
                            is RestoreOutcome.Restored -> Toast(
                                Localization.getString("reports_restore_done")
                                    .replaceFirst("%s", outcome.events.toString())
                                    .replaceFirst("%s", outcome.safetyBackup.fileName.toString()),
                            )
                            is RestoreOutcome.Rejected -> Toast(rejection(outcome.code), error = true)
                            is RestoreOutcome.Failed -> Toast(Localization.getString("reports_restore_failed").replace("%s", outcome.diagnostic), error = true)
                        }
                    }
                },
                onCancel = { pendingRestore = null },
            )
        }
        pendingDelete?.let { path ->
            ConfirmDialogComponent(
                title = Localization.getString("reports_delete_title"),
                text = Localization.getString("reports_delete_text").replace("%s", path.fileName.toString()),
                confirmText = Localization.getString("reports_backup_delete"),
                cancelText = Localization.getString("bout_back"),
                onConfirm = {
                    pendingDelete = null
                    run {
                        withContext(Dispatchers.IO) { checkNotNull(backups).delete(path) }
                        selectedBackup = null
                        refreshBackups()
                    }
                },
                onCancel = { pendingDelete = null },
            )
        }
    }

    /** Court, category, bout, judge and action, combined with AND (`AUD-003`). */
    @Composable
    private fun Filters(report: CompetitionReport, filter: HistoryFilter, onChange: (HistoryFilter) -> Unit) {
        val history = report.history
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Selector(
                    "reports_filter_court", filter.court?.let { Localization.getString("courts_court").replace("%d", it.toString()) },
                    history.map { it.court }.distinct().sorted().map { court -> Localization.getString("courts_court").replace("%d", court.toString()) to court },
                    Modifier.weight(1f),
                ) { onChange(filter.copy(court = it)) }
                Selector(
                    "reports_filter_category", history.firstOrNull { it.categoryId == filter.categoryId }?.categoryTitle,
                    history.distinctBy { it.categoryId }.map { it.categoryTitle to it.categoryId },
                    Modifier.weight(2f),
                ) { onChange(filter.copy(categoryId = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Selector(
                    "reports_filter_session", history.firstOrNull { it.sessionId == filter.sessionId }?.let(::sessionName),
                    history.filter { filter.categoryId == null || it.categoryId == filter.categoryId }.distinctBy { it.sessionId }
                        .sortedBy { it.number }.map { sessionName(it) to it.sessionId },
                    Modifier.weight(1f),
                ) { onChange(filter.copy(sessionId = it)) }
                Selector(
                    "reports_filter_judge", filter.judge,
                    history.mapNotNull { it.judge }.distinct().sorted().map { it to it },
                    Modifier.weight(1f),
                ) { onChange(filter.copy(judge = it)) }
                Selector(
                    "reports_filter_action", filter.action?.let(HistoryText::action),
                    history.map { it.action }.distinct().map { HistoryText.action(it) to it },
                    Modifier.weight(1f),
                ) { onChange(filter.copy(action = it)) }
            }
        }
    }

    private fun sessionName(row: HistoryRow) = Localization.getString(
        if (row.kind == org.mass.report.SessionKind.TECHNICAL) "reports_performance_number" else "bout_number",
    ).replace("%d", row.number.toString())

    /** A labelled choice with «Все» first; [options] are shown text and value. */
    @Composable
    private fun <T> Selector(titleKey: String, shown: String?, options: List<Pair<String, T>>, modifier: Modifier, onPick: (T?) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box(modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ROW_SHAPE)
                    .background(Color.White.copy(alpha = 0.6f))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.DropdownList) { open = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(Localization.getString(titleKey), color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                Text(shown ?: Localization.getString("reports_filter_all"), color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(Localization.getString("reports_filter_all")) }, onClick = { open = false; onPick(null) })
                options.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
                }
            }
        }
    }

    /** One input: when, which bout, who, what, and why it did or did not count. */
    @Composable
    private fun HistoryLine(row: HistoryRow, zone: ZoneId) {
        val counted = row.status == HistoryStatus.COUNTED || row.status == HistoryStatus.APPLIED
        Column(
            modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(Color.White.copy(alpha = if (counted) 0.75f else 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val who = listOfNotNull(
                HistoryText.source(row.source),
                row.seat?.let { seat -> Localization.getString("reports_history_seat").replace("%d", seat.toString()) + (row.judge?.let { " $it" } ?: "") },
            ).joinToString(" · ")
            Text(
                "${TIME.format(row.occurredAt.atZone(zone))} · ${sessionName(row)} · $who",
                color = Colors.BROWN.color.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val what = listOf(HistoryText.action(row.action), HistoryText.side(row.side), HistoryText.value(row)).filter(String::isNotBlank).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(what, color = Colors.BROWN.color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(HistoryText.status(row.status), color = if (counted) Colors.PRIMARY.color else Colors.RED.color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            row.reason?.takeIf(String::isNotBlank)?.let {
                Text(Localization.getString("reports_history_reason").replace("%s", it), color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
            }
            row.referenceEventId?.let {
                Text(Localization.getString("reports_history_reference").replace("%s", it.take(8)), color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun BackupLine(entry: BackupEntry, selected: Boolean, onClick: () -> Unit) {
        val header = entry.header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(if (selected) Colors.PRIMARY.color else Color.White.copy(alpha = 0.6f))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val content = if (selected) Color.White else Colors.BROWN.color
            Text(entry.name, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (header == null) {
                    Localization.getString("reports_backup_legacy")
                } else {
                    Localization.getString("reports_backup_line")
                        .replaceFirst("%s", backupReason(header.reason))
                        .replaceFirst("%s", header.events.toString())
                },
                color = content.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    private fun backupReason(reason: String) = when (reason) {
        JournalBackups.BEFORE_IMPORT, JournalBackups.BEFORE_RESTORE, JournalBackups.AFTER_SESSION, JournalBackups.MANUAL ->
            Localization.getString("reports_backup_reason_${reason.replace('-', '_')}")
        else -> reason
    }

    /** Every refusal code of `JournalBackup` has a `reports_restore_rejected_<code>` string. */
    val REJECTIONS = listOf(
        JournalBackup.UNREADABLE, JournalBackup.EMPTY, JournalBackup.UNKNOWN_FORMAT, JournalBackup.UNSUPPORTED_VERSION,
        JournalBackup.NEWER_SCHEMA, JournalBackup.INVALID_ROW, JournalBackup.COUNT_MISMATCH, JournalBackup.CHECKSUM_MISMATCH,
        JournalBackup.DUPLICATE_EVENT, JournalBackup.UNKNOWN_PEER,
    )

    private fun rejection(code: String) =
        if (code in REJECTIONS) Localization.getString("reports_restore_rejected_$code") else Localization.getString("reports_restore_failed").replace("%s", code)

    @Composable
    private fun ActionButton(key: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), style: ButtonStyles = ButtonStyles.Primary, onClick: () -> Unit) {
        ButtonComponent(
            text = Localization.getString(key),
            style = style,
            onclick = onClick,
            enabled = enabled,
            modifier = modifier,
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, color = Colors.BROWN.color.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
    }

    @Composable
    private fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier = modifier.fillMaxHeight().clip(PANEL_SHAPE).background(Colors.GRAY.color).padding(16.dp), content = content)
    }

    @Composable
    private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
    private val CARD_SHAPE = RoundedCornerShape(16.dp)
    private val ROW_SHAPE = RoundedCornerShape(8.dp)
}
