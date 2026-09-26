package org.mass.ui.server_status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mass.Server
import org.mass.ServerRuntimeState
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent

/**
 * Shows whether the server and its database accept work. With [failuresOnly] it stays hidden while the server runs, so
 * other screens show only a failure, which is never hidden from the operator (`UI-007`).
 */
@Composable
fun ServerStatusComponent(modifier: Modifier = Modifier, failuresOnly: Boolean = false) {
    val state by Server.runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val failed = state as? ServerRuntimeState.Failed
    if (failuresOnly && failed == null && state != ServerRuntimeState.Stopped) return
    val text = when (val current = state) {
        ServerRuntimeState.Stopped -> Localization.getString("server_status_stopped")
        ServerRuntimeState.Starting -> Localization.getString("server_status_starting")
        is ServerRuntimeState.Running -> Localization.getString("server_status_running").format(current.port)
        is ServerRuntimeState.Failed -> Localization.getString("server_status_failed").format(current.diagnostic)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (failed != null) FAILURE_COLOR else Colors.SECONDARY.color)
            .padding(horizontal = 15.dp, vertical = 8.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (failed != null) Color.White else Colors.PRIMARY.color,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (failed != null || state == ServerRuntimeState.Stopped) {
            Spacer(Modifier.width(15.dp))
            ButtonComponent(
                text = Localization.getString("server_status_restart"),
                onclick = { scope.launch(Dispatchers.IO) { Server.runtime.restart() } },
            )
        }
    }
}

private val FAILURE_COLOR = Color(0xFFB3261E)
