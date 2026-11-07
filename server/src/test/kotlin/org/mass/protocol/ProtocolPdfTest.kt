package org.mass.protocol

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.mass.applications.ApplicationDiscipline
import org.mass.applications.CompetitionApplications
import org.mass.applications.CompetitionApplicationsJournal
import org.mass.applications.CompetitionApplicationsService
import org.mass.domain.PeerId
import org.mass.draw.CompetitionCourtsService
import org.mass.draw.CompetitionDrawJournal
import org.mass.draw.CompetitionDrawService
import org.mass.draw.CompetitionOfficials
import org.mass.draw.DrawFixtures.category
import org.mass.draw.DrawOutcome
import org.mass.draw.DrawRejection
import org.mass.draw.DrawSystem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-008 §8: «Протокол хода соревнований» from the confirmed draws, read back with PDFBox's text extraction. */
class ProtocolPdfTest {
    private val peerId = PeerId("00000000-0000-4000-8000-000000000001")
    private var nextSeed = 7L

    private val five = category(
        List(5) { "Тренер $it" },
        id = "kerugi-33",
        names = listOf("Иванов Иван Иванович", "Петров Пётр", "Сидоров Семён Сергеевич", "Кузнецов Кирилл", "Орлов Олег"),
    )
    private val three = category(List(3) { "Тренер $it" }, id = "kerugi-36", weight = "до 36 кг")
    private val forms = category(List(4) { "Тренер $it" }, id = "forms", discipline = ApplicationDiscipline.FREE_FORM)
    private val pending = category(List(2) { "Тренер $it" }, id = "kerugi-40", weight = "до 40 кг")

    @Test
    fun `one page per confirmed category with the bracket, the bout numbers and the officials`() {
        val (applications, draws, courts) = services(competition(five, three, forms))
        draws.drawAll()
        draws.setSystem("kerugi-33", DrawSystem.SINGLE_ELIMINATION)
        draws.confirm()
        courts.setCourtCount(2)
        courts.placeCategory("forms", court = 2, position = 0)
        assertEquals(
            DrawOutcome.Done,
            courts.setOfficials(CompetitionOfficials("  г. Новосибирск, СК «Север» ", "Смирнов А. А.", "Волкова Е. В.")),
        )

        val document = ProtocolDocument.of(courts.board(), applications.history.value.current!!.applications)!!
        assertEquals(listOf("kerugi-33", "kerugi-36", "forms"), document.pages.map { it.category.id })
        assertFalse(document.preliminary)
        val pages = text(ProtocolPdf.render(document))
        assertEquals(3, pages.size)

        // Every page: title, competition, date and place, signature lines with names; the court with two courts.
        pages.forEach { page ->
            assertTrue(ProtocolPdf.TITLE in page, page)
            assertTrue("Первенство города" in page)
            assertTrue("15.11.2025, г. Новосибирск, СК «Север»" in page)
            assertTrue("Главный судья" in page && "Смирнов А. А." in page)
            assertTrue("Главный секретарь" in page && "Волкова Е. В." in page)
            assertFalse(ProtocolPdf.PRELIMINARY in page)
        }
        assertTrue("2014-2013 г.р. Младшие юноши, до 33 кг (Площадка № 1)" in pages[0], pages[0])
        assertTrue("Комплекс свободный, 2014-2013 г.р. Младшие юноши (Площадка № 2)" in pages[2], pages[2])

        // Elimination of five: four bouts, the entries as «Фамилия И. О. (город)».
        val bracket = pages[0]
        assertEquals(4, Regex("Бой № \\d+").findAll(bracket).count())
        assertTrue("Иванов И. И. (Город)" in bracket, bracket)
        assertTrue("Петров П. (Город)" in bracket)
        val numbers = courts.board().numbers.filterKeys { it.categoryId == "kerugi-33" }.values.sorted()
        numbers.forEach { assertTrue("Бой № $it" in bracket, "Бой № $it") }

        // Round robin of three: three rounds of one bout; performances in their order.
        assertEquals(3, Regex("Бой № \\d+").findAll(pages[1]).count())
        assertTrue("Круг 3" in pages[1])
        assertEquals(4, Regex("Выступление № \\d+").findAll(pages[2]).count())
        assertTrue("Порядок выступлений" in pages[2])
    }

    @Test
    fun `one category on its own, a preliminary mark while categories wait for the draw, nothing without a draw`() {
        val (applications, draws, courts) = services(competition(five, pending))
        val current = { applications.history.value.current!!.applications }
        assertNull(ProtocolDocument.of(courts.board(), current()))

        draws.shuffle("kerugi-33")
        draws.confirm()
        val document = ProtocolDocument.of(courts.board(), current(), setOf("kerugi-33"))!!
        assertTrue(document.preliminary)
        val page = text(ProtocolPdf.render(document)).single()
        assertTrue(ProtocolPdf.PRELIMINARY in page)
        // One court: no court in the title; no officials yet: empty signature lines.
        assertFalse("Площадка" in page)
        assertTrue("2014-2013 г.р. Младшие юноши, до 33 кг" in page)
        assertNull(ProtocolDocument.of(courts.board(), current(), setOf("kerugi-40")))

        // Officials are validated before they are journaled; unchanged ones write nothing.
        assertEquals(
            DrawOutcome.Rejected(DrawRejection.INVALID_OFFICIALS),
            courts.setOfficials(CompetitionOfficials(place = "x".repeat(CompetitionOfficials.MAX_LENGTH + 1))),
        )
        val journalSize = draws.journal().size
        assertEquals(DrawOutcome.Done, courts.setOfficials(CompetitionOfficials()))
        assertEquals(journalSize, draws.journal().size)
    }

    @Test
    fun `names are shortened to the surname and initials`() {
        assertEquals("Иванов И. И.", ProtocolDocument.shortName(" Иванов  Иван Иванович "))
        assertEquals("Петров", ProtocolDocument.shortName("Петров"))
        assertEquals("Иванов И. И. (Город)", ProtocolDocument.entryName(five.entries[0]))
    }

    private fun text(pdf: ByteArray): List<String> = Loader.loadPDF(pdf).use { document ->
        assertTrue(document.pages.all { it.mediaBox.width > it.mediaBox.height }, "A4 landscape")
        (1..document.numberOfPages).map { number ->
            PDFTextStripper().apply {
                startPage = number
                endPage = number
            }.getText(document)
        }
    }

    private fun services(competition: CompetitionApplications): Triple<CompetitionApplicationsService, CompetitionDrawService, CompetitionCourtsService> {
        val applications = CompetitionApplicationsService(CompetitionApplicationsJournal.InMemory(), backup = null)
        applications.import(competition, replaceConfirmed = false)
        val draws = CompetitionDrawService(CompetitionDrawJournal.InMemory(), applications.history, peerId, seeds = { nextSeed++ })
        return Triple(applications, draws, CompetitionCourtsService(draws, peerId))
    }

    private fun competition(vararg categories: org.mass.applications.ApplicationCategory) = CompetitionApplications(
        competitionName = "Первенство города",
        competitionDate = LocalDate.of(2025, 11, 15),
        readerVersion = "df-template-v1",
        files = emptyList(),
        categories = categories.toList(),
    )
}
