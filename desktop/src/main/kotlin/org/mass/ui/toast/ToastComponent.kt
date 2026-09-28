package org.mass.ui.toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.mass.enums.Colors
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.cross_icon

/** A message of the operator screens; [error] stays until it is closed, other messages hide by themselves. */
data class Toast(val text: String, val error: Boolean = false, val id: Long = System.nanoTime())

/**
 * Shows [toast] at the top of the screen: red for errors, primary otherwise. [onDismiss] is called by the cross and, for a
 * message that is not an error, after [durationMillis].
 */
@Composable
fun ToastComponent(toast: Toast?, onDismiss: () -> Unit, modifier: Modifier = Modifier, durationMillis: Long = 6_000) {
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    LaunchedEffect(toast?.id) {
        if (toast != null && !toast.error) {
            delay(durationMillis)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it },
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        val accent = if (current.error) ERROR_COLOR else Colors.PRIMARY.color
        val shape = RoundedCornerShape(14.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 10.dp)
                .widthIn(max = 760.dp)
                .shadow(12.dp, shape)
                .clip(shape)
                // A dark base under a faded accent keeps the white text and cross readable over any screen.
                .background(BASE_COLOR)
                .background(accent.copy(alpha = 0.35f))
                .border(2.dp, accent, shape)
                .padding(start = 24.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                current.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(18.dp))
            Image(
                painterResource(Res.drawable.cross_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button, onClick = onDismiss),
            )
        }
    }
}

private val ERROR_COLOR = Color(0xFFB3261E)
private val BASE_COLOR = Color(0xF21F1E24)
