package org.mass.protocol

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.mass.applications.CategoryEntry
import org.mass.draw.BoutSide
import org.mass.draw.Bouts
import org.mass.draw.DrawFormat
import org.mass.draw.ItemRef
import org.mass.draw.drawKey
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * Renders «Протокол хода соревнований» in the organiser's layout (ADR-008 §8) with Apache PDFBox: A4 landscape, one page
 * per category with the title, the competition, its date and place, the category and its court, the bracket with «Бой № N»
 * at every bout (byes start in the second-round column), and the signature lines of the chief judge and the chief secretary.
 * Round robins list their rounds, technical disciplines the performance order. The bundled Montserrat (OFL) carries Cyrillic.
 */
object ProtocolPdf {
    const val TITLE = "Протокол хода соревнований"
    const val PRELIMINARY = "Нумерация предварительная"

    fun render(document: ProtocolDocument): ByteArray = PDDocument().use { pdf ->
        val fonts = Fonts(font(pdf, "Montserrat-Regular.ttf"), font(pdf, "Montserrat-Bold.ttf"))
        document.pages.forEach { page ->
            val pdfPage = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            pdf.addPage(pdfPage)
            PDPageContentStream(pdf, pdfPage).use { stream -> PageLayout(Canvas(stream, fonts), document, page).draw() }
        }
        pdf.documentInformation.title = TITLE
        ByteArrayOutputStream().also(pdf::save).toByteArray()
    }

    private fun font(pdf: PDDocument, name: String): PDType0Font =
        checkNotNull(ProtocolPdf::class.java.getResourceAsStream("/fonts/$name")) { "Font $name is not bundled" }
            .use { PDType0Font.load(pdf, it) }
}

private class Fonts(val regular: PDType0Font, val bold: PDType0Font)

/** Text and lines on one page; characters the font cannot show become «?» instead of failing the whole protocol. */
private class Canvas(private val stream: PDPageContentStream, private val fonts: Fonts) {
    private val printable = mutableMapOf<Pair<Boolean, Int>, Boolean>()

    fun width(text: String, size: Float, bold: Boolean = false): Float =
        font(bold).getStringWidth(safe(text, bold)) / 1000f * size

    fun text(text: String, x: Float, y: Float, size: Float, bold: Boolean = false, align: Align = Align.LEFT, maxWidth: Float? = null) {
        var shown = safe(text, bold)
        if (maxWidth != null && width(shown, size, bold) > maxWidth) {
            while (shown.isNotEmpty() && width("$shown…", size, bold) > maxWidth) shown = shown.dropLast(1)
            shown = "${shown.trimEnd()}…"
        }
        val start = when (align) {
            Align.LEFT -> x
            Align.CENTRE -> x - width(shown, size, bold) / 2
            Align.RIGHT -> x - width(shown, size, bold)
        }
        stream.beginText()
        stream.setFont(font(bold), size)
        stream.newLineAtOffset(start, y)
        stream.showText(shown)
        stream.endText()
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 0.8f) {
        stream.setLineWidth(width)
        stream.moveTo(x1, y1)
        stream.lineTo(x2, y2)
        stream.stroke()
    }

    fun box(x: Float, y: Float, width: Float, height: Float) {
        stream.setLineWidth(0.8f)
        stream.addRect(x, y, width, height)
        stream.stroke()
    }

    private fun font(bold: Boolean) = if (bold) fonts.bold else fonts.regular

    private fun safe(text: String, bold: Boolean): String = buildString {
        text.codePoints().forEach { codePoint ->
            val ok = printable.getOrPut(bold to codePoint) {
                runCatching { font(bold).encode(String(Character.toChars(codePoint))) }.isSuccess
            }
            if (ok) appendCodePoint(codePoint) else append('?')
        }
    }
}

private enum class Align { LEFT, CENTRE, RIGHT }

private class PageLayout(private val canvas: Canvas, private val document: ProtocolDocument, private val page: ProtocolPage) {
    private val width = PDRectangle.A4.height
    private val height = PDRectangle.A4.width
    private val left = MARGIN
    private val right = width - MARGIN
    private val entries: Map<String, CategoryEntry> = page.category.entries.associateBy { it.drawKey }

    fun draw() {
        val top = header()
        val bottom = MARGIN + 44f
        when (page.draw.format) {
            DrawFormat.ELIMINATION -> elimination(top, bottom)
            DrawFormat.ROUND_ROBIN -> roundRobin(top, bottom)
            DrawFormat.PERFORMANCE_ORDER -> performanceOrder(top, bottom)
            DrawFormat.NO_BOUTS -> Unit
        }
        signatures()
    }

    /** Title, competition, date and place, the category and its court; returns the top of the bracket area. */
    private fun header(): Float {
        val centre = width / 2
        var y = height - MARGIN - 14f
        canvas.text(ProtocolPdf.TITLE, centre, y, 15f, bold = true, align = Align.CENTRE)
        if (document.preliminary) canvas.text(ProtocolPdf.PRELIMINARY, right, y + 4f, 8f, align = Align.RIGHT)
        y -= 18f
        canvas.text(document.competitionName, centre, y, 11f, bold = true, align = Align.CENTRE, maxWidth = right - left)
        y -= 14f
        val date = DATE.format(document.competitionDate)
        val place = document.officials.place
        canvas.text(if (place.isEmpty()) date else "$date, $place", centre, y, 9.5f, align = Align.CENTRE, maxWidth = right - left)
        y -= 18f
        val court = if (document.courts > 1) " (Площадка № ${page.court})" else ""
        canvas.text(page.title + court, centre, y, 11f, bold = true, align = Align.CENTRE, maxWidth = right - left)
        return y - 14f
    }

    private fun signatures() {
        val y = MARGIN + 12f
        val half = (right - left) / 2
        listOf("Главный судья" to document.officials.chiefJudge, "Главный секретарь" to document.officials.chiefSecretary)
            .forEachIndexed { index, (role, name) ->
                val x = left + half * index
                canvas.text(role, x, y, 9.5f, bold = true)
                val lineStart = x + canvas.width(role, 9.5f, bold = true) + 8f
                val lineEnd = lineStart + 110f
                canvas.line(lineStart, y - 2f, lineEnd, y - 2f, 0.6f)
                canvas.text(name, lineEnd + 8f, y, 9.5f, maxWidth = x + half - lineEnd - 16f)
            }
    }

    /**
     * Rounds left to right as in the bracket view: first-round boxes only for the pairs that fight, entries with a bye in the
     * second-round column, an empty box for every winner still to come and «Бой № N» above the box its winner goes to.
     */
    private fun elimination(top: Float, bottom: Float) {
        val positions = page.draw.positions
        val size = positions.size
        val rounds = Bouts.rounds(page.draw)
        val occupied = mutableListOf(positions.map { it != null })
        val known = mutableListOf(positions)
        (1..rounds).forEach { level ->
            occupied += occupied[level - 1].chunked(2).map { (first, second) -> first || second }
            known += (0 until (size shr level)).map { index ->
                val first = index * 2
                when {
                    occupied[level - 1][first] && occupied[level - 1][first + 1] -> null
                    occupied[level - 1][first] -> known[level - 1][first]
                    else -> known[level - 1][first + 1]
                }
            }
        }
        fun bout(round: Int, index: Int) = occupied[round - 1][index * 2] && occupied[round - 1][index * 2 + 1]

        val slot = minOf((top - bottom) / size, 36f)
        val column = (right - left) / (rounds + 1)
        val gap = minOf(28f, column * 0.22f)
        val boxWidth = column - gap
        val boxHeight = minOf(slot * 0.82f, 20f)
        val fontSize = (boxHeight * 0.5f).coerceIn(5f, 9f)
        val labelSize = maxOf(5f, fontSize - 1.5f)
        fun x(level: Int) = left + column * level
        fun centre(level: Int, index: Int) = top - slot * ((index + 0.5f) * (1 shl level))

        // Connectors of every bout: from both children to the box of its winner.
        (1..rounds).forEach { round ->
            (0 until (size shr round)).filter { bout(round, it) }.forEach { index ->
                val childRight = x(round - 1) + boxWidth
                val middle = childRight + gap / 2
                val upper = centre(round - 1, index * 2)
                val lower = centre(round - 1, index * 2 + 1)
                val parent = centre(round, index)
                canvas.line(childRight, upper, middle, upper)
                canvas.line(childRight, lower, middle, lower)
                canvas.line(middle, upper, middle, lower)
                canvas.line(middle, parent, x(round), parent)
            }
        }
        (0..rounds).forEach { level ->
            var boutIndex = 0
            (0 until (size shr level)).forEach { index ->
                val shown = if (level == 0) bout(1, index / 2) else occupied[level][index]
                if (!shown) return@forEach
                val y = centre(level, index)
                canvas.box(x(level), y - boxHeight / 2, boxWidth, boxHeight)
                known[level][index]?.let { key ->
                    canvas.text(name(key), x(level) + 3f, y - fontSize * 0.35f, fontSize, maxWidth = boxWidth - 6f)
                }
                // The box of a winner carries the number of the bout that decides it.
                if (level > 0 && bout(level, index)) {
                    val number = page.numbers[ItemRef(page.category.id, level, boutIndex++)]
                    canvas.text(boutLabel(number), x(level) + 2f, y + boxHeight / 2 + 2f, labelSize, bold = true)
                }
            }
        }
    }

    private fun roundRobin(top: Float, bottom: Float) {
        val lines = Bouts.of(page.draw).groupBy { it.round }.flatMap { (round, bouts) ->
            listOf(Line("Круг $round", bold = true)) + bouts.map { bout ->
                val number = page.numbers[ItemRef(page.category.id, bout.round, bout.index)]
                Line("${boutLabel(number)}   ${side(bout.first)} — ${side(bout.second)}")
            }
        }
        flow(lines, top, bottom)
    }

    private fun performanceOrder(top: Float, bottom: Float) {
        val lines = listOf(Line("Порядок выступлений", bold = true)) + page.draw.entryKeys.mapIndexed { index, key ->
            val number = page.numbers[ItemRef(page.category.id, 1, index)]
            Line("${number?.let { "Выступление № $it" } ?: "Выступление"}   ${name(key)}")
        }
        flow(lines, top, bottom)
    }

    /** Lines top to bottom, in up to three columns when they do not fit one; smaller only when three are not enough. */
    private fun flow(lines: List<Line>, top: Float, bottom: Float) {
        val area = top - bottom
        val columns = (1..3).firstOrNull { lines.size * LINE <= area * it } ?: 3
        val perColumn = (lines.size + columns - 1) / columns
        val lineHeight = minOf(LINE, area / maxOf(1, perColumn))
        val size = minOf(9f, lineHeight * 0.68f)
        val columnWidth = (right - left) / columns
        lines.forEachIndexed { index, line ->
            val column = index / perColumn
            val row = index % perColumn
            canvas.text(
                line.text,
                left + columnWidth * column,
                top - lineHeight * (row + 1),
                size,
                bold = line.bold,
                maxWidth = columnWidth - 12f,
            )
        }
    }

    private fun side(side: BoutSide) = when (side) {
        is BoutSide.Entry -> name(side.key)
        is BoutSide.WinnerOf -> ""
    }

    private fun name(key: String) = entries[key]?.let(ProtocolDocument::entryName) ?: key

    private fun boutLabel(number: Int?) = number?.let { "Бой № $it" } ?: "Бой"

    private data class Line(val text: String, val bold: Boolean = false)

    private companion object {
        const val MARGIN = 28f
        const val LINE = 14f
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
