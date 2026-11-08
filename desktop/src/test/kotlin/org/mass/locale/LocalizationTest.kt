package org.mass.locale

import org.mass.State
import org.mass.report.HistoryStatus
import org.mass.screens.ReportsScreen
import org.mass.ui.RejectionText
import org.mass.ui.report.HistoryText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-012 §8 (`SYS-004`): the desktop UI is complete in Russian and English. Every key exists in both languages, every key
 * the sources and the code families use is defined, and no desktop source holds a Cyrillic literal outside `Localization`.
 */
class LocalizationTest {
    private val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `both languages have the same keys`() {
        val russian = Localization.keys("ru")
        val english = Localization.keys("en")
        assertEquals(emptySet(), russian - english, "missing in English")
        assertEquals(emptySet(), english - russian, "missing in Russian")
        assertTrue(russian.size > 400)
    }

    @Test
    fun `every key the sources and the code families use is defined`() {
        val literal = Regex("""getString\(\s*"([a-zA-Z0-9_-]+)"""")
        val used = sources.flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1] }.toList() }.toSet() +
            HistoryText.ACTIONS.map { "history_action_$it" } +
            HistoryText.REASONS.map { "history_reason_$it" } +
            HistoryStatus.entries.map { "history_status_${it.name.lowercase()}" } +
            listOf("judge_device", "operator", "server").map { "history_source_$it" } +
            RejectionText.CODES.map { "rejection_$it" } +
            ReportsScreen.REJECTIONS.map { "reports_restore_rejected_$it" } +
            org.mass.enums.Routes.entries.filter { it.path.isNotEmpty() }.map { it.path }
        val missing = used.filter { it !in Localization.keys("ru") }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `no desktop source shows a hard-coded Cyrillic string`() {
        val cyrillicLiteral = Regex(""""[^"\n]*[Ѐ-ӿ][^"\n]*"""")
        val offenders = sources.filter { it.name != "Localization.kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                // Doc comments may quote the Russian UI; only code lines count.
                if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                cyrillicLiteral.find(code)?.let { "${file.name}:${index + 1}: ${it.value}" }
            }
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `the pilot starts in Russian and switches without a restart`() {
        assertEquals("ru", State.currentLocale)
        assertEquals("Отчёты и резервные копии", Localization.getString("reports"))
        State.currentLocale = "en"
        try {
            assertEquals("Reports and backups", Localization.getString("reports"))
        } finally {
            State.currentLocale = "ru"
        }
    }
}
