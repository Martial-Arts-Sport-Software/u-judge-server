package org.mass.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mass.DeviceConnectionState
import org.mass.OperatorDevice
import org.mass.PairingRequests
import org.mass.PendingPairingRequest
import org.mass.Server
import org.mass.ServerRuntimeState
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.screen_header.ScreenHeaderComponent

/**
 * Operator pairing (`DEV-004`-`DEV-006`): the server verification code, pending judges to approve or reject, and paired
 * devices with platform, connection state and revocation. Decisions are written to the journal before the list changes.
 */
object DevicesConnectionScreen : Screen {
    @Composable
    override fun Load() {
        val runtimeState by Server.runtime.state.collectAsState()
        val pairing = Server.runtime.pairingRequests
        val changes by pairing.changes.collectAsState()
        val registry = remember(pairing, changes) { pairing.operatorRegistry() }
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf<String?>(null) }
        var revokeCandidate by remember { mutableStateOf<OperatorDevice?>(null) }

        fun decide(action: PairingRequests.() -> Unit) {
            scope.launch {
                error = try {
                    withContext(Dispatchers.IO) { pairing.action() }
                    null
                } catch (exception: Exception) {
                    Localization.getString("devices_action_failed").format(exception.message ?: exception::class.simpleName)
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Column(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                VerificationCode(runtimeState)
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FAILURE_COLOR).padding(10.dp),
                    )
                }
                Spacer(Modifier.height(15.dp))
                Row(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(Colors.GRAY.color).padding(25.dp),
                ) {
                    Panel(Localization.getString("devices_connection_available"), Modifier.weight(1f)) {
                        if (registry.pending.isEmpty()) item { EmptyText("devices_pending_empty") }
                        items(registry.pending, key = PendingPairingRequest::requestId) { request ->
                            PendingRow(
                                request,
                                onApprove = { decide { approve(request.requestId) } },
                                onReject = { decide { reject(request.requestId) } },
                            )
                        }
                    }
                    Spacer(Modifier.width(25.dp))
                    Panel(Localization.getString("devices_connection_connected"), Modifier.weight(1f)) {
                        if (registry.devices.isEmpty()) item { EmptyText("devices_paired_empty") }
                        items(registry.devices, key = OperatorDevice::requestId) { device ->
                            DeviceRow(device, onRevoke = { revokeCandidate = device })
                        }
                    }
                }
            }
        }

        revokeCandidate?.let { device ->
            AlertDialog(
                onDismissRequest = { revokeCandidate = null },
                title = { Text(Localization.getString("devices_revoke_title")) },
                text = { Text(Localization.getString("devices_revoke_text").format(device.surname, platformName(device.platform))) },
                confirmButton = {
                    TextButton(onClick = {
                        revokeCandidate = null
                        decide { revoke(device.requestId) }
                    }) { Text(Localization.getString("devices_revoke")) }
                },
                dismissButton = {
                    TextButton(onClick = { revokeCandidate = null }) { Text(Localization.getString("devices_cancel")) }
                },
            )
        }
    }

    @Composable
    private fun VerificationCode(state: ServerRuntimeState) {
        val code = (state as? ServerRuntimeState.Running)?.verificationCode
        val text = if (code == null) {
            Localization.getString("devices_code_unavailable")
        } else {
            Localization.getString("devices_code").format("${code.take(3)} ${code.drop(3)}")
        }
        Text(
            text = text,
            color = Colors.PRIMARY.color,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(Colors.SECONDARY.color)
                .padding(15.dp)
                .semantics { contentDescription = text },
        )
    }

    @Composable
    private fun Panel(
        title: String,
        modifier: Modifier,
        content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(Colors.SECONDARY.color).padding(15.dp),
        ) {
            Text(text = title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(20.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize(), content = content)
        }
    }

    @Composable
    private fun PendingRow(request: PendingPairingRequest, onApprove: () -> Unit, onReject: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            DeviceText(request.surname, platformName(request.platform), Localization.getString("devices_state_pending"), Modifier.weight(1f))
            ButtonComponent(text = Localization.getString("devices_approve"), onclick = onApprove)
            Spacer(Modifier.width(8.dp))
            ButtonComponent(text = Localization.getString("devices_reject"), style = ButtonStyles.Secondary, onclick = onReject)
        }
    }

    @Composable
    private fun DeviceRow(device: OperatorDevice, onRevoke: () -> Unit) {
        val state = when {
            device.revoked -> Localization.getString("devices_state_revoked")
            device.connectionState == DeviceConnectionState.CONNECTED -> Localization.getString("devices_state_connected")
            else -> Localization.getString("devices_state_disconnected")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            DeviceText(device.surname, platformName(device.platform), state, Modifier.weight(1f))
            if (!device.revoked) {
                ButtonComponent(text = Localization.getString("devices_revoke"), style = ButtonStyles.Secondary, onclick = onRevoke)
            }
        }
    }

    @Composable
    private fun DeviceText(surname: String, platform: String, state: String, modifier: Modifier) {
        val description = "$surname, $platform, $state"
        Column(modifier.semantics { contentDescription = description }) {
            Text(text = surname, color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodyLarge)
            Text(text = "$platform · $state", color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun EmptyText(key: String) {
        Text(text = Localization.getString(key), color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodyMedium)
    }

    private fun platformName(platform: String) = when (platform) {
        "android" -> "Android"
        "ios" -> "iOS"
        else -> platform
    }

    private val FAILURE_COLOR = Color(0xFFB3261E)
}
