package org.mass.ui.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.painterResource
import org.mass.enums.Colors
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.cross_icon

/**
 * Confirmation modal of the operator screens: light card, dark title and text, a transparent cancel and a primary action.
 * @param showClose shows the cross in the corner, which cancels like the cancel button
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConfirmDialogComponent(
    title: String,
    text: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    showClose: Boolean = false,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            animateTransition = true,
        ),
    ) {
        Box(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Colors.SECONDARY.color)
                .padding(22.dp),
        ) {
            Column {
                Text(
                    title,
                    color = Colors.BROWN.color,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = if (showClose) 36.dp else 0.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(text, color = Colors.BROWN.color.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    DialogButton(cancelText, Color.Transparent, Colors.BROWN.color.copy(alpha = 0.65f), onCancel)
                    DialogButton(confirmText, Colors.PRIMARY.color, Color.White, onConfirm)
                }
            }
            if (showClose) {
                Image(
                    painterResource(Res.drawable.cross_icon),
                    contentDescription = cancelText,
                    colorFilter = ColorFilter.tint(Colors.BROWN.color.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button, onClickLabel = cancelText, onClick = onCancel),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, background: Color, content: Color, onClick: () -> Unit) {
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
