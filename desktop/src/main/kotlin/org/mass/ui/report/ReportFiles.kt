package org.mass.ui.report

import org.mass.locale.Localization
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The system file dialogs of «Отчёты и резервные копии» (ADR-012): a local path the operator chooses (`REP-003`). */
object ReportFiles {
    /** The file to save a report suggested as [name] with [extension]; null when the operator cancels. */
    fun save(name: String, extension: String): File? {
        val dialog = FileDialog(null as Frame?, Localization.getString("reports_save_title"), FileDialog.SAVE).apply {
            file = "${safe(name)}.$extension"
            isVisible = true
        }
        val chosen = dialog.file ?: return null
        return File(dialog.directory, if (chosen.endsWith(".$extension", ignoreCase = true)) chosen else "$chosen.$extension")
    }

    /** A file to open, such as a backup or a seal image; null when the operator cancels. */
    fun open(titleKey: String): File? {
        val dialog = FileDialog(null as Frame?, Localization.getString(titleKey), FileDialog.LOAD).apply { isVisible = true }
        return dialog.file?.let { File(dialog.directory, it) }
    }

    /** A file name without the characters Windows and macOS forbid. */
    fun safe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
}
