package org.mass.ui.protocol

import org.mass.locale.Localization
import org.mass.protocol.ProtocolDocument
import org.mass.protocol.ProtocolPdf
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Saving «Протокол хода соревнований» (ADR-008 §8): the system save dialog, then the PDF written off the UI thread. */
object ProtocolFiles {
    /** The file the operator chose for a protocol suggested as [name]; null when they cancel. */
    fun choose(name: String): File? {
        val dialog = FileDialog(null as Frame?, Localization.getString("protocol_save_title"), FileDialog.SAVE).apply {
            file = "${name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()}.pdf"
            isVisible = true
        }
        val chosen = dialog.file ?: return null
        return File(dialog.directory, if (chosen.endsWith(".pdf", ignoreCase = true)) chosen else "$chosen.pdf")
    }

    /** Renders [document] into [file]; blocking, so it runs on `Dispatchers.IO`. */
    fun write(document: ProtocolDocument, file: File) {
        file.writeBytes(ProtocolPdf.render(document))
    }
}
