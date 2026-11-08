package org.mass.ui.court

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mass.applications.ApplicationDiscipline
import org.mass.draw.CategoryDrawState
import org.mass.draw.CategoryProgress
import org.mass.draw.CourtItem
import org.mass.draw.CourtPanel
import org.mass.draw.CourtsBoard
import org.mass.draw.ItemRef
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.bracket.entryLabel
import org.mass.ui.button.ButtonComponent

/**
 * The court panel of ADR-008 §9 (`SES-003`), the right column of the operator screen: «Текущий бой», «Следующий бой» with its
 * category and both sides, and the bouts waiting on the court; a click on one makes it the next. [onStart] is the seam of
 * I2a's «Начать»: without it there is no start button, because bouts cannot start yet.
 */
@Composable
fun CourtPanelComponent(
    panel: CourtPanel,
    onPick: (ItemRef) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onStart: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            Localization.getString("courts_court").replace("%d", panel.court.toString()),
            color = Colors.PRIMARY.color,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Section(
            panel.current?.let { Localization.getString("court_panel_current_number").replace("%d", it.number.toString()) }
                ?: Localization.getString("court_panel_current"),
        ) {
            val current = panel.current
            if (current == null) {
                Muted(Localization.getString("court_panel_current_none"))
            } else {
                ItemSides(current)
            }
        }
        val next = panel.next
        Section(
            when (next) {
                null -> Localization.getString("court_panel_next")
                is CourtItem.Bout -> Localization.getString("court_panel_next_number").replace("%d", next.number.toString())
                is CourtItem.Performance -> Localization.getString("court_panel_next_performance").replace("%d", next.number.toString())
            },
            badge = Localization.getString("court_panel_picked").takeIf { panel.nextSelected },
        ) {
            if (next == null) {
                Muted(Localization.getString("court_panel_next_none"))
            } else {
                Text(next.category.title, color = Colors.BROWN.color, style = MaterialTheme.typography.labelSmall)
                ItemSides(next)
            }
        }
        if (onStart != null && next != null) {
            ButtonComponent(
                text = Localization.getString("court_panel_start"),
                onclick = onStart,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        if (panel.preliminary) Muted(Localization.getString("brackets_numbering_preliminary"))
        Text(
            Localization.getString("court_panel_queue").replace("%d", panel.queue.size.toString()),
            color = Colors.BROWN.color.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        if (panel.queue.isNotEmpty()) Muted(Localization.getString("court_panel_pick_hint"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(panel.queue, key = { it.ref.toString() }) { item ->
                val chosen = item.ref == next?.ref
                val content = if (chosen) Color.White else Colors.PRIMARY.color
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ROW_SHAPE)
                        .background(if (chosen) Colors.PRIMARY.color else SECONDARY_ROW_COLOR)
                        .then(
                            if (enabled) {
                                Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onPick(item.ref) }
                            } else {
                                Modifier
                            },
                        )
                        .semantics(mergeDescendants = true) { selected = chosen }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        "№ ${item.number} · ${sidesText(item)}",
                        color = content,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.category.title,
                        color = content.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * «Все категории» (ADR-008 §11, `CMP-008`): every category court by court in the operator's order with its progress and its
 * bout or performance numbers. [selectedId] is highlighted; a click selects a category.
 */
@Composable
fun AllCategoriesComponent(
    board: CourtsBoard,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        (1..board.plan.count).forEach { court ->
            val categories = board.categoriesOf(court)
            item(key = "court-$court") {
                Text(
                    Localization.getString("courts_court").replace("%d", court.toString()) + " · " + categories.size,
                    color = Colors.PRIMARY.color,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (court > 1) 8.dp else 0.dp),
                )
            }
            if (categories.isEmpty()) {
                item(key = "court-$court-empty") { Muted(Localization.getString("courts_court_empty")) }
            }
            items(categories, key = { it.category.id }) { state ->
                CategoryProgressRow(board, state, selected = state.category.id == selectedId, onClick = { onSelect(state.category.id) })
            }
        }
    }
}

@Composable
private fun CategoryProgressRow(board: CourtsBoard, state: CategoryDrawState, selected: Boolean, onClick: () -> Unit) {
    val content = if (selected) Color.White else Colors.PRIMARY.color
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(if (selected) Colors.PRIMARY.color else SECONDARY_ROW_COLOR)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = state.category.title
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(state.category.title, color = content, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressBadge(board.progress(state), inverted = !selected)
            numbersText(board, state)?.let {
                Text(it, color = content.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** «свободна», «в процессе», «завершена», «нет поединков» or «требует новой жеребьёвки» in the error colour. */
@Composable
fun ProgressBadge(progress: CategoryProgress, inverted: Boolean) {
    val (background, content) = when {
        progress == CategoryProgress.NEEDS_REDRAW -> FAILURE_COLOR to Color.White
        inverted -> Colors.SECONDARY.color to Colors.PRIMARY.color
        else -> Colors.PRIMARY.color to Color.White
    }
    Text(
        progressLabel(progress),
        color = content,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun progressLabel(progress: CategoryProgress): String = Localization.getString(
    when (progress) {
        CategoryProgress.FREE -> "brackets_status_confirmed"
        CategoryProgress.IN_PROGRESS -> "brackets_status_started"
        CategoryProgress.NO_BOUTS -> "brackets_status_no_bouts"
        CategoryProgress.NEEDS_REDRAW -> "brackets_status_needs_redraw"
        CategoryProgress.COMPLETED -> "brackets_status_completed"
    },
)

/** «Бои № 1-4, 12-13, 16» or «Выступления № 5-9»; null while the category has no numbers. */
fun numbersText(board: CourtsBoard, state: CategoryDrawState): String? {
    val numbers = board.numbers.filterKeys { it.categoryId == state.category.id }.values.sorted()
    if (numbers.isEmpty()) return null
    val key = if (state.category.discipline == ApplicationDiscipline.KERUGI) "courts_bouts" else "courts_performances"
    return Localization.getString(key).replace("%s", ranges(numbers))
}

/** Consecutive numbers as ranges: `1, 2, 3, 5` → `1-3, 5`. */
fun ranges(numbers: List<Int>): String {
    val parts = mutableListOf<String>()
    var start = numbers.first()
    var previous = start
    numbers.drop(1).plus(Int.MIN_VALUE).forEach { number ->
        if (number != previous + 1) {
            parts += if (start == previous) "$start" else "$start-$previous"
            start = number
        }
        previous = number
    }
    return parts.joinToString(", ")
}

/** «Иванов И. — Петров П.» for a bout, the entry for a performance. */
fun sidesText(item: CourtItem): String = when (item) {
    is CourtItem.Bout -> "${entryLabel(item.blue, full = false)} — ${entryLabel(item.red, full = false)}"
    is CourtItem.Performance -> entryLabel(item.entry, full = false)
}

@Composable
private fun ItemSides(item: CourtItem) {
    when (item) {
        is CourtItem.Bout -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SideBox(entryLabel(item.blue, full = true), Colors.BLUE.color)
            SideBox(entryLabel(item.red, full = true), Colors.RED.color)
        }
        is CourtItem.Performance -> SideBox(entryLabel(item.entry, full = true), Colors.PRIMARY.color)
    }
}

@Composable
private fun SideBox(text: String, color: Color) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth().clip(ROW_SHAPE).background(color).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Section(title: String, badge: String? = null, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().clip(CARD_SHAPE).background(Colors.SECONDARY.color).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            badge?.let {
                Spacer(Modifier.width(6.dp))
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Colors.PRIMARY.color).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        content()
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, color = Colors.BROWN.color.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
}

private val ROW_SHAPE = RoundedCornerShape(8.dp)
private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val SECONDARY_ROW_COLOR = Color.White.copy(alpha = 0.48f)
private val FAILURE_COLOR = Color(0xFFB3261E)
