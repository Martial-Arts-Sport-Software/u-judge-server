package org.mass.ui.bracket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mass.applications.CategoryEntry
import org.mass.draw.BoutSide
import org.mass.draw.Bouts
import org.mass.draw.CategoryDraw
import org.mass.draw.DrawFormat
import org.mass.draw.drawKey
import org.mass.enums.Colors
import org.mass.locale.Localization

/** Sizes of one bracket drawing: the preview panel is compact, the full-screen view is large (owner's mockups). */
data class BracketScale(val boxWidth: Dp, val boxHeight: Dp, val slotHeight: Dp, val columnGap: Dp, val fullNames: Boolean) {
    companion object {
        val Compact = BracketScale(boxWidth = 180.dp, boxHeight = 34.dp, slotHeight = 44.dp, columnGap = 36.dp, fullNames = false)
        val Large = BracketScale(boxWidth = 300.dp, boxHeight = 56.dp, slotHeight = 72.dp, columnGap = 56.dp, fullNames = true)
    }
}

/**
 * The drawn bracket of a category (ADR-008 §7): an elimination bracket with rounds left to right, byes placed straight in
 * the second round as in the organiser's protocols and a final «Победитель» box, or the rounds of a round robin. Entries in
 * [clashes] are outlined: separation could not keep them apart.
 */
@Composable
fun BracketComponent(
    draw: CategoryDraw,
    entries: List<CategoryEntry>,
    clashes: Set<String>,
    modifier: Modifier = Modifier,
    scale: BracketScale = BracketScale.Compact,
) {
    val byKey = entries.associateBy { it.drawKey }
    val label = { key: String -> byKey[key]?.let { entryLabel(it, scale.fullNames) } ?: key }
    Box(modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        when (draw.format) {
            DrawFormat.ELIMINATION -> Elimination(draw, label, clashes, scale)
            DrawFormat.ROUND_ROBIN -> RoundRobin(draw, label, scale)
            DrawFormat.PERFORMANCE_ORDER, DrawFormat.NO_BOUTS -> Unit
        }
    }
}

/** «Иванов-Аов И.» in the compact view, «Иванов-Аов Иван» at full size; a pair joins its athletes with a slash. */
fun entryLabel(entry: CategoryEntry, full: Boolean): String = entry.athletes.joinToString(" / ") { athlete ->
    val parts = athlete.fullName.trim().split(Regex("\\s+"))
    when {
        parts.size < 2 -> parts.first()
        full -> "${parts[0]} ${parts[1]}"
        else -> "${parts[0]} ${parts[1].first()}."
    }
}

/**
 * Nodes are addressed by level: level 0 are the first-round slots, level `r` the winners of round `r`. A node is occupied
 * when any slot below it holds an entry; it is known before the results when only one of its children is occupied (the
 * entry passed with a bye).
 */
@Composable
private fun Elimination(draw: CategoryDraw, label: (String) -> String, clashes: Set<String>, scale: BracketScale) {
    val size = draw.positions.size
    val rounds = Bouts.rounds(draw)
    val occupied = mutableListOf(draw.positions.map { it != null })
    val known = mutableListOf(draw.positions)
    (1..rounds).forEach { level ->
        occupied += occupied[level - 1].chunked(2).map { (first, second) -> first || second }
        known += (0 until (size shr level)).map { index ->
            val first = index * 2
            val second = first + 1
            when {
                occupied[level - 1][first] && occupied[level - 1][second] -> null
                occupied[level - 1][first] -> known[level - 1][first]
                else -> known[level - 1][second]
            }
        }
    }
    fun bout(round: Int, index: Int) = occupied[round - 1][index * 2] && occupied[round - 1][index * 2 + 1]
    val column = scale.boxWidth + scale.columnGap
    fun centre(level: Int, index: Int): Dp = scale.slotHeight * ((index + 0.5f) * (1 shl level))

    val width = column * rounds + scale.boxWidth
    val height = scale.slotHeight * size
    Box(Modifier.size(width, height)) {
        Canvas(Modifier.size(width, height)) {
            val stroke = 2.dp.toPx()
            (1..rounds).forEach { round ->
                (0 until (size shr round)).filter { bout(round, it) }.forEach { index ->
                    val colour = if (round == rounds) WINNER_COLOR else Colors.PRIMARY.color
                    val childRight = (column * (round - 1) + scale.boxWidth).toPx()
                    val middle = childRight + scale.columnGap.toPx() / 2
                    val top = centre(round - 1, index * 2).toPx()
                    val bottom = centre(round - 1, index * 2 + 1).toPx()
                    val parent = centre(round, index).toPx()
                    drawLine(colour, Offset(childRight, top), Offset(middle, top), stroke, StrokeCap.Round)
                    drawLine(colour, Offset(childRight, bottom), Offset(middle, bottom), stroke, StrokeCap.Round)
                    drawLine(colour, Offset(middle, top), Offset(middle, bottom), stroke, StrokeCap.Round)
                    drawLine(colour, Offset(middle, parent), Offset((column * round).toPx(), parent), stroke, StrokeCap.Round)
                }
            }
        }
        (0 until rounds).forEach { level ->
            (0 until (size shr level)).forEach { index ->
                // Round 1 shows only the pairs that fight; an entry with a bye starts in the round-2 column.
                val shown = if (level == 0) bout(1, index / 2) else occupied[level][index]
                if (!shown) return@forEach
                val key = known[level][index]
                EntryBox(
                    text = key?.let(label).orEmpty(),
                    upper = index % 2 == 0,
                    clash = key != null && key in clashes,
                    scale = scale,
                    x = column * level,
                    y = centre(level, index),
                )
            }
        }
        WinnerBox(scale, x = column * rounds, y = centre(rounds, 0))
    }
}

@Composable
private fun EntryBox(text: String, upper: Boolean, clash: Boolean, scale: BracketScale, x: Dp, y: Dp) {
    val background = if (upper) Colors.PRIMARY.color else LIGHT_ENTRY_COLOR
    val content = if (upper) Color.White else Colors.BROWN.color
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .offset(x, y - scale.boxHeight / 2)
            .size(scale.boxWidth, scale.boxHeight)
            .clip(BOX_SHAPE)
            .background(background)
            .then(if (clash) Modifier.border(2.dp, CLASH_COLOR, BOX_SHAPE) else Modifier)
            .semantics { contentDescription = text }
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text,
            color = content,
            style = if (scale.fullNames) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The final box of the mockups; the winner's name comes with the results (I2b). */
@Composable
private fun WinnerBox(scale: BracketScale, x: Dp, y: Dp) {
    val winnerWidth = scale.boxWidth * 0.8f
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .offset(x, y - scale.boxHeight / 2)
            .size(winnerWidth, scale.boxHeight)
            .clip(BOX_SHAPE)
            .background(WINNER_COLOR)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            Localization.getString("brackets_winner"),
            color = Colors.BROWN.color,
            style = if (scale.fullNames) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Round robin: every round with its pairs; places are decided by wins (ADR-007 §4). */
@Composable
private fun RoundRobin(draw: CategoryDraw, label: (String) -> String, scale: BracketScale) {
    val bouts = Bouts.of(draw)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        bouts.groupBy { it.round }.forEach { (round, roundBouts) ->
            Text(
                Localization.getString("brackets_round").replace("%d", round.toString()),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            roundBouts.forEach { bout ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(bout.first, bout.second).forEachIndexed { side, entry ->
                        val key = (entry as BoutSide.Entry).key
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .width(scale.boxWidth)
                                .clip(BOX_SHAPE)
                                .background(if (side == 0) Colors.PRIMARY.color else LIGHT_ENTRY_COLOR)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                label(key),
                                color = if (side == 0) Color.White else Colors.BROWN.color,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Text(
            Localization.getString("brackets_round_robin_places"),
            color = Colors.BROWN.color.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val BOX_SHAPE = RoundedCornerShape(8.dp)
// The mockups' lower slot: a light tint of the primary colour with dark text.
private val LIGHT_ENTRY_COLOR = Color(0xFFB79CF2)
private val WINNER_COLOR = Color(0xFF3DD65C)
private val CLASH_COLOR = Color(0xFFB3261E)
