package org.mass.ui.tooltip

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.mass.enums.Colors
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows [text] under [content] after the pointer rests on it; without [text] the content is laid out as is.
 * The tooltip is anchored to the content, not to the pointer, and flips above it at the bottom of the window.
 * @param modifier applied to the anchor; its size constraints reach [content] as if it were applied to it directly
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TooltipComponent(
    text: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, text) {
        shown = false
        if (hovered && text != null) {
            delay(500.milliseconds)
            shown = true
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(300))

    Box(
        propagateMinConstraints = true,
        modifier = modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        content()
        if (text != null && (shown || alpha > 0f)) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { BelowAnchor(gap) },
                properties = PopupProperties(focusable = false),
            ) {
                Text(
                    text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .alpha(alpha)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Colors.BROWN.color)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Centred under the anchor, [gap] away from it; above it when there is no room below, always inside the window. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceAtLeast(0),
        )
    }
}
