package org.mass.ui.bout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.mass.locale.Localization
import java.time.Duration
import java.time.Instant

/** The clock as the snapshot carries it (ADR-010 §5): while it runs, [remainingMillis] is the time left at [runningSince]. */
data class ClockFace(
    val period: String,
    val round: Int,
    val golden: Boolean,
    val remainingMillis: Long,
    val runningSince: Instant?,
) {
    fun remainingAt(now: Instant): Long {
        val since = runningSince ?: return remainingMillis
        return (remainingMillis - Duration.between(since, now).toMillis()).coerceIn(0, remainingMillis)
    }

    /** «Раунд 1», «Перерыв», «Отдых перед дополнительным раундом» or «Дополнительный раунд». */
    val periodLabel: String
        get() = when {
            period == "round" && golden -> Localization.getString("bout_period_golden_round")
            period == "round" -> Localization.getString("bout_period_round").replace("%d", round.toString())
            golden -> Localization.getString("bout_period_golden_break")
            else -> Localization.getString("bout_period_break")
        }
}

/** `m:ss`, rounded up so the clock shows 0:00 only when the time is over. */
fun clockText(millis: Long): String {
    val seconds = (millis + 999) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The current time, refreshed while the screen is shown so a running clock counts down without pushes. */
@Composable
fun rememberNow(periodMillis: Long = 200): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            now = Instant.now()
        }
    }
    return now
}
