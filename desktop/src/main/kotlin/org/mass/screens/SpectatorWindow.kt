package org.mass.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.MutableStateFlow
import org.mass.Server
import org.mass.SessionView
import org.mass.bout.CourtBouts
import org.mass.bout.SpectatorView
import org.mass.domain.KerugiCompetitor
import org.mass.draw.CourtItem
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.bout.ClockFace
import org.mass.ui.bout.clockText
import org.mass.ui.bout.rememberNow
import org.mass.ui.TypographyManager.getTypography
import java.awt.GraphicsEnvironment
import java.time.Instant

/**
 * The spectator screen (ADR-010 §8, `UI-004`, `UI-005`): a second window for the external monitor with the court, the
 * category, the bout, the sides, the score, the penalties, the period and the clock, plus the call screen «Приглашаются /
 * Готовятся». It renders the read-only projection a connected device without a seat receives and has no control that
 * changes state; closing it only closes the window.
 */
@Composable
fun SpectatorWindow(onClose: () -> Unit) {
    val screens = remember { runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList() }.getOrDefault(emptyList()) }
    // The last attached display is the external monitor; with one display the window opens beside the operator's.
    val external = screens.takeIf { it.size > 1 }?.last()?.defaultConfiguration?.bounds
    val state = rememberWindowState(
        placement = if (external != null) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        position = external?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
        size = external?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 720.dp),
    )
    Window(onCloseRequest = onClose, title = "U'Judge - ${Localization.getString("spectator_title")}", state = state) {
        MaterialTheme(typography = getTypography()) {
            SpectatorContent()
        }
    }
}

@Composable
private fun SpectatorContent() {
    val runtimeState by Server.runtime.state.collectAsState()
    val court = remember(runtimeState) { Server.runtime.courtBouts }
    val boutRevision by (court?.bouts?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
    val drawRevision by (Server.runtime.competitionDraws?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
    val view = remember(court, boutRevision, drawRevision) { court?.let(CourtBouts::spectator) }
    val now = rememberNow()
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().background(Color(0xFF14110F)).padding(32.dp),
    ) {
        val session = view?.session?.takeIf { it.state != "completed" && it.state != "cancelled" }
        if (session != null) {
            Board(session, now, Modifier.weight(1f))
        } else {
            Calls(view, large = true, modifier = Modifier.weight(1f), finished = view?.session)
        }
        if (session != null) Calls(view, large = false, modifier = Modifier.fillMaxWidth(), finished = null)
    }
}

@Composable
private fun Board(session: SessionView, now: Instant, modifier: Modifier) {
    val timer = session.timer
    val face = ClockFace(timer.period, timer.round, timer.goldenRound, timer.remainingMillis, timer.runningSince?.let(Instant::parse))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            Localization.getString("spectator_court").replace("%d", session.courtNumber.toString()) + " · " +
                Localization.getString("bout_number").replace("%d", session.boutNumber.toString()) + " · " + session.categoryTitle,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(face.periodLabel, color = Colors.SECONDARY.color, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            clockText(face.remainingAt(now)),
            color = if (timer.running) Color.White else Colors.SECONDARY.color,
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { contentDescription = face.periodLabel },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            listOf(KerugiCompetitor.BLUE, KerugiCompetitor.RED).forEach { side ->
                val blue = side == KerugiCompetitor.BLUE
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp))
                        .background(if (blue) Colors.BLUE.color else Colors.RED.color).padding(24.dp),
                ) {
                    Text(
                        if (blue) session.blue.label else session.red.label,
                        color = Color.White,
                        fontSize = 32.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (blue) session.score.blue else session.score.red).toString(),
                        color = Color.White,
                        fontSize = 160.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = Localization.getString(if (blue) "bout_blue" else "bout_red") },
                    )
                    Text(
                        Localization.getString("bout_gamjeom_count")
                            .replace("%d", (if (blue) session.score.gamjeom.blue else session.score.gamjeom.red).toString()),
                        color = Color.White,
                        fontSize = 28.sp,
                    )
                }
            }
        }
    }
}

/** «Приглашаются» is the next bout of the court, «Готовятся» the bout after it (ADR-008 §9, ADR-010 §8). */
@Composable
private fun Calls(view: SpectatorView?, large: Boolean, modifier: Modifier, finished: SessionView?) {
    val size = if (large) 40.sp else 22.sp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (large) 28.dp else 8.dp)) {
        finished?.result?.let { result ->
            Text(
                Localization.getString("bout_number").replace("%d", finished.boutNumber.toString()) + " · " +
                    Localization.getString("bout_result")
                        .replaceFirst("%s", if (result.winner == KerugiCompetitor.BLUE) finished.blue.label else finished.red.label)
                        .replaceFirst("%s", Localization.getString("bout_reason_${result.reason}")),
                color = Colors.SECONDARY.color,
                fontSize = size,
                fontWeight = FontWeight.Bold,
            )
        }
        if (large && view?.invited == null && finished == null) {
            Text(Localization.getString("spectator_idle"), color = Color.White, fontSize = size)
        }
        listOf("spectator_invited" to view?.invited, "spectator_preparing" to view?.preparing).forEach { (title, item) ->
            if (item != null) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Localization.getString(title), color = Colors.SECONDARY.color, fontSize = size, fontWeight = FontWeight.Bold)
                        Text(callText(item), color = Color.White, fontSize = size)
                    }
                }
            }
        }
    }
}

private fun callText(item: CourtItem): String {
    val number = Localization.getString("bout_number").replace("%d", item.number.toString())
    val sides = when (item) {
        is CourtItem.Bout -> Localization.getString("spectator_vs")
            .replaceFirst("%s", CourtBouts.label(item.blue))
            .replaceFirst("%s", CourtBouts.label(item.red))
        is CourtItem.Performance -> CourtBouts.label(item.entry)
    }
    return "$number · ${item.category.title} · $sides"
}
