package org.mass.ui.bracket

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.jetbrains.compose.resources.painterResource
import org.mass.applications.CategoryEntry
import org.mass.draw.BoutSide
import org.mass.draw.Bouts
import org.mass.draw.CategoryDraw
import org.mass.draw.DrawFormat
import org.mass.draw.drawKey
import org.mass.enums.Colors
import org.mass.locale.Localization
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.user_icon
import kotlin.math.roundToInt

/** Sizes of one bracket drawing: the preview panel is compact, the full-screen view is large (owner's mockups). */
data class BracketScale(val boxWidth: Dp, val boxHeight: Dp, val slotHeight: Dp, val columnGap: Dp, val fullNames: Boolean) {
    companion object {
        val Compact = BracketScale(boxWidth = 180.dp, boxHeight = 34.dp, slotHeight = 44.dp, columnGap = 36.dp, fullNames = false)
        val Large = BracketScale(boxWidth = 300.dp, boxHeight = 56.dp, slotHeight = 72.dp, columnGap = 56.dp, fullNames = true)
    }
}

/**
 * Editing of an elimination bracket: a click picks an entry as in the list, dragging an entry onto another one swaps them.
 * [highlighted] entries are drawn in the primary colour.
 */
data class BracketEditing(
    val highlighted: Set<String>,
    val onPick: (String) -> Unit,
    val onSwap: (String, String) -> Unit,
)

/**
 * The drawn bracket of a category (ADR-008 §7): an elimination bracket with rounds left to right, byes placed straight in
 * the second round as in the organiser's protocols and a final «Победитель» box, or the rounds of a round robin. The upper
 * side of every pair is blue and the lower one red, also while the entry is not known yet. With [editing] the entries of
 * an elimination bracket can be dragged onto each other.
 */
@Composable
fun BracketComponent(
    draw: CategoryDraw,
    entries: List<CategoryEntry>,
    modifier: Modifier = Modifier,
    scale: BracketScale = BracketScale.Compact,
    editing: BracketEditing? = null,
) {
    val byKey = entries.associateBy { it.drawKey }
    val label = { key: String -> byKey[key]?.let { entryLabel(it, scale.fullNames) } ?: key }
    Box(modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        when (draw.format) {
            DrawFormat.ELIMINATION -> Elimination(draw, label, scale, editing)
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
private fun Elimination(draw: CategoryDraw, label: (String) -> String, scale: BracketScale, editing: BracketEditing?) {
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

    // Every shown box: round 1 shows only the pairs that fight; an entry with a bye starts in the round-2 column.
    val slots = (0 until rounds).flatMap { level ->
        (0 until (size shr level)).mapNotNull { index ->
            val shown = if (level == 0) bout(1, index / 2) else occupied[level][index]
            if (shown) Slot(known[level][index], upper = index % 2 == 0, x = column * level, y = centre(level, index)) else null
        }
    }
    var dragged by remember { mutableStateOf<Slot?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // The entry under the centre of the dragged box: dropping there swaps the two.
    val target = dragged?.let { from ->
        with(density) {
            val centreX = (from.x + scale.boxWidth / 2).toPx() + dragOffset.x
            val centreY = from.y.toPx() + dragOffset.y
            slots.firstOrNull { slot ->
                slot.key != null && slot.key != from.key &&
                    centreX in slot.x.toPx()..(slot.x + scale.boxWidth).toPx() &&
                    centreY in (slot.y - scale.boxHeight / 2).toPx()..(slot.y + scale.boxHeight / 2).toPx()
            }
        }
    }
    val currentTarget by rememberUpdatedState(target)
    val currentEditing by rememberUpdatedState(editing)
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
        slots.forEach { slot ->
            val key = slot.key
            val moving = dragged == slot
            val interaction = if (editing != null && key != null) {
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { editing.onPick(key) }
                    .pointerInput(slot) {
                        detectDragGestures(
                            onDragStart = {
                                dragged = slot
                                dragOffset = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount
                            },
                            onDragEnd = {
                                currentTarget?.key?.let { other -> currentEditing?.onSwap(key, other) }
                                dragged = null
                            },
                            onDragCancel = { dragged = null },
                        )
                    }
            } else {
                Modifier
            }
            // The place the entry is dragged from stays marked in its side colour.
            if (moving) DragOrigin(slot, scale)
            EntryBox(
                text = key?.let(label).orEmpty(),
                upper = slot.upper,
                highlighted = key != null && (key in editing?.highlighted.orEmpty() || moving || target == slot),
                scale = scale,
                x = slot.x,
                y = slot.y,
                modifier = if (moving) {
                    Modifier.zIndex(1f).offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }.alpha(0.9f).then(interaction)
                } else {
                    interaction
                },
            )
        }
        WinnerBox(scale, x = column * rounds, y = centre(rounds, 0))
    }
}

/** The empty place of a dragged entry: the side colour as the border, faded inside, a person in the middle. */
@Composable
private fun DragOrigin(slot: Slot, scale: BracketScale) {
    val colour = sideColor(slot.upper)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(slot.x, slot.y - scale.boxHeight / 2)
            .size(scale.boxWidth, scale.boxHeight)
            .clip(BOX_SHAPE)
            .background(colour.copy(alpha = 0.2f))
            .border(2.dp, colour, BOX_SHAPE),
    ) {
        Image(
            painterResource(Res.drawable.user_icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colour),
            modifier = Modifier.size(scale.boxHeight * 0.6f),
        )
    }
}

/** One shown box of an elimination bracket; [key] is null until the entry is known from the results. */
private data class Slot(val key: String?, val upper: Boolean, val x: Dp, val y: Dp)

@Composable
private fun EntryBox(text: String, upper: Boolean, highlighted: Boolean, scale: BracketScale, x: Dp, y: Dp, modifier: Modifier) {
    val background by animateColorAsState(if (highlighted) Colors.PRIMARY.color else sideColor(upper), tween(300))
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .offset(x, y - scale.boxHeight / 2)
            .then(modifier)
            .size(scale.boxWidth, scale.boxHeight)
            .clip(BOX_SHAPE)
            .background(background)
            .semantics { contentDescription = text }
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text,
            color = Color.White,
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
                                .background(sideColor(upper = side == 0))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                label(key),
                                color = Color.White,
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

private fun sideColor(upper: Boolean) = if (upper) Colors.BLUE.color else Colors.RED.color

private val BOX_SHAPE = RoundedCornerShape(8.dp)
private val WINNER_COLOR = Color(0xFF3DD65C)
