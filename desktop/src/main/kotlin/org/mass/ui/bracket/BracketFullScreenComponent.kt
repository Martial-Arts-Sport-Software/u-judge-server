package org.mass.ui.bracket

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.mass.applications.ApplicationDiscipline
import org.mass.draw.BoutSide
import org.mass.draw.Bouts
import org.mass.draw.CategoryDraw
import org.mass.draw.CategoryDrawState
import org.mass.draw.CourtsBoard
import org.mass.draw.DrawFormat
import org.mass.draw.drawKey
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.court.AllCategoriesComponent
import org.mass.ui.tooltip.TooltipComponent
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.cross_icon

/** Kerugi categories whose shown draw has bouts: the ones the full-screen view and its arrows go through. */
fun CategoryDrawState.hasBracket(): Boolean =
    category.discipline.isBout &&
        (shown?.format == DrawFormat.ELIMINATION || shown?.format == DrawFormat.ROUND_ROBIN)

/**
 * The full-screen bracket of ADR-008 §7: close, «← <category> · Категория N из M →», «Все категории», chips of the categories
 * of the same discipline and age class, «Список / Сетка» and the download of the category's protocol. Bout numbers show for
 * a confirmed draw; [onDownload] is null while the category has no confirmed draw.
 */
@Composable
fun BracketFullScreenComponent(
    board: CourtsBoard,
    categoryId: String,
    onSelect: (String) -> Unit,
    onOpenElsewhere: (String) -> Unit,
    onClose: () -> Unit,
    onDownload: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val navigable = board.ordered.filter { it.hasBracket() }
    val index = navigable.indexOfFirst { it.category.id == categoryId }
    val state = navigable.getOrNull(index)
    var grid by remember { mutableStateOf(true) }
    var allCategories by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().background(BACKGROUND).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TooltipComponent(Localization.getString("fullscreen_close")) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(SHAPE)
                        .background(Colors.PRIMARY.color)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = Localization.getString("fullscreen_close") },
                ) {
                    Image(painterResource(Res.drawable.cross_icon), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(16.dp))
                }
            }
            Arrow("←", Localization.getString("fullscreen_previous"), enabled = index > 0) { onSelect(navigable[index - 1].category.id) }
            Text(
                state?.let {
                    "${it.category.title} · " + Localization.getString("fullscreen_category_of")
                        .replaceFirst("%d", (index + 1).toString())
                        .replaceFirst("%d", navigable.size.toString())
                }.orEmpty(),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Arrow("→", Localization.getString("fullscreen_next"), enabled = index in 0 until navigable.size - 1) {
                onSelect(navigable[index + 1].category.id)
            }
            Toggle(Localization.getString("fullscreen_all_categories"), allCategories) { allCategories = !allCategories }
            Toggle(Localization.getString("brackets_view_list"), !grid && !allCategories) {
                grid = false
                allCategories = false
            }
            Toggle(Localization.getString("brackets_view_bracket"), grid && !allCategories) {
                grid = true
                allCategories = false
            }
            ButtonComponent(
                text = Localization.getString("fullscreen_download"),
                onclick = { state?.let { onDownload?.invoke(it.category.id) } },
                enabled = onDownload != null && state != null,
                tooltip = Localization.getString("fullscreen_download_unavailable").takeIf { onDownload == null },
                modifier = Modifier.width(220.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        if (state != null && !allCategories) {
            val siblings = navigable.filter {
                it.category.discipline == state.category.discipline && it.category.section.title == state.category.section.title
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                siblings.forEach { sibling ->
                    Toggle(sibling.category.weight ?: sibling.category.title, sibling.category.id == state.category.id) {
                        onSelect(sibling.category.id)
                    }
                }
            }
        }
        val numbering = when {
            board.frozen -> "brackets_numbering_frozen"
            board.preliminary -> "brackets_numbering_preliminary"
            else -> null
        }
        numbering?.let { Text(Localization.getString(it), color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall) }
        Box(Modifier.fillMaxWidth().weight(1f).clip(CARD_SHAPE).background(Color.White.copy(alpha = 0.7f))) {
            when {
                allCategories -> AllCategoriesComponent(
                    board = board,
                    selectedId = state?.category?.id,
                    onSelect = { id ->
                        allCategories = false
                        if (navigable.any { it.category.id == id }) onSelect(id) else onOpenElsewhere(id)
                    },
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
                state == null -> Unit
                else -> {
                    val draw = checkNotNull(state.shown)
                    // Numbers belong to the confirmed draw; a pending preview has none until it is confirmed.
                    val numbered = state.preview == null
                    val number = { round: Int, boutIndex: Int ->
                        if (numbered) board.number(state.category.id, round, boutIndex) else null
                    }
                    if (grid) {
                        BracketComponent(
                            draw,
                            state.category.entries,
                            Modifier.fillMaxSize(),
                            scale = BracketScale.Large,
                            number = number,
                            progress = board.results[state.category.id].takeIf { numbered },
                        )
                    } else {
                        BoutList(draw, state, number, Modifier.fillMaxSize().padding(16.dp))
                    }
                }
            }
        }
    }
}

/** The bouts of a category in number order: both sides, or the bouts whose winners meet while they are not known. */
@Composable
private fun BoutList(draw: CategoryDraw, state: CategoryDrawState, number: (Int, Int) -> Int?, modifier: Modifier) {
    val entries = state.category.entries.associateBy { it.drawKey }
    val bouts = Bouts.of(draw).sortedWith(compareBy({ number(it.round, it.index) ?: Int.MAX_VALUE }, { it.round }, { it.index }))
    fun side(side: BoutSide): String = when (side) {
        is BoutSide.Entry -> entries[side.key]?.let { entryLabel(it, full = true) } ?: side.key
        is BoutSide.WinnerOf -> number(side.round, side.index)
            ?.let { Localization.getString("bouts_winner_of").replace("%d", it.toString()) }
            ?: Localization.getString("bouts_winner")
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        items(bouts) { bout ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    number(bout.round, bout.index)?.let { Localization.getString("brackets_bout_number").replace("%d", it.toString()) }
                        ?: Localization.getString("brackets_round").replace("%d", bout.round.toString()),
                    color = Colors.PRIMARY.color,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(110.dp),
                )
                listOf(bout.first to Colors.BLUE.color, bout.second to Colors.RED.color).forEach { (boutSide, colour) ->
                    Text(
                        side(boutSide),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clip(SHAPE).background(colour).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Arrow(symbol: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipComponent(hint) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(SHAPE)
                .background(if (enabled) Colors.PRIMARY.color else Colors.PRIMARY.color.copy(alpha = 0.35f))
                .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick) else Modifier)
                .semantics { contentDescription = hint },
        ) {
            Text(symbol, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else Colors.PRIMARY.color,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(SHAPE)
            .background(if (active) Colors.PRIMARY.color else Color.White.copy(alpha = 0.6f))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

private val SHAPE = RoundedCornerShape(8.dp)
private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val BACKGROUND = Color(0xFFF3E6FF)
