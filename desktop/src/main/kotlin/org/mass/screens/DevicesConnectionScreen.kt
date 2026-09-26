package org.mass.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.mass.ui.screen_header.ScreenHeaderComponent
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.check_icon
import u_judge_server.desktop.generated.resources.cross_icon

/**
 * Operator pairing (`DEV-004`-`DEV-006`) laid out as Figma "Device configuration" V1: paired and available devices on the
 * left, the server information on the right. Decisions are written to the journal before the lists change.
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
                    Localization.getString("devices_action_failed").replace("%s", exception.message ?: exception::class.simpleName.orEmpty())
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderComponent(modifier = Modifier.fillMaxHeight(0.08f).fillMaxWidth())
            Row(Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 15.dp)) {
                Row(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                        .clip(PANEL_SHAPE)
                        .background(Colors.GRAY.color)
                        .border(1.dp, PANEL_BORDER, PANEL_SHAPE)
                        .padding(25.dp),
                ) {
                    val paired = registry.devices.filterNot(OperatorDevice::revoked)
                    DeviceList(Localization.getString("devices_connection_connected"), Modifier.weight(1f)) {
                        if (paired.isEmpty()) item { EmptyText("devices_paired_empty") }
                        itemsIndexed(paired, key = { _, device -> device.requestId }) { index, device ->
                            PairedRow(index + 1, device, onRevoke = { revokeCandidate = device })
                        }
                    }
                    Spacer(Modifier.width(25.dp))
                    DeviceList(Localization.getString("devices_connection_available"), Modifier.weight(1f)) {
                        if (registry.pending.isEmpty()) item { EmptyText("devices_pending_empty") }
                        itemsIndexed(registry.pending, key = { _, request -> request.requestId }) { index, request ->
                            PendingRow(
                                index + 1,
                                request,
                                onApprove = { decide { approve(request.requestId) } },
                                onReject = { decide { reject(request.requestId) } },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(25.dp))
                ServerPanel(runtimeState, error, Modifier.weight(0.35f))
            }
        }

        revokeCandidate?.let { device ->
            AlertDialog(
                onDismissRequest = { revokeCandidate = null },
                title = { Text(Localization.getString("devices_revoke_title")) },
                text = {
                    Text(
                        Localization.getString("devices_revoke_text")
                            .replaceFirst("%s", device.surname)
                            .replaceFirst("%s", platformName(device.platform)),
                    )
                },
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

    /** "Конфигурация устройств": where judges connect, the code they must see and whether the server accepts work. */
    @Composable
    private fun ServerPanel(state: ServerRuntimeState, error: String?, modifier: Modifier) {
        val running = state as? ServerRuntimeState.Running
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .fillMaxHeight()
                .clip(PANEL_SHAPE)
                .background(Colors.GRAY.color)
                .border(1.dp, PANEL_BORDER, PANEL_SHAPE)
                .padding(25.dp),
        ) {
            Text(
                text = Localization.getString("devices_config_title"),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            if (running == null) {
                InfoPill(
                    when (state) {
                        is ServerRuntimeState.Failed -> Localization.getString("devices_server_failed")
                        else -> Localization.getString("devices_code_unavailable")
                    },
                    background = if (state is ServerRuntimeState.Failed) FAILURE_COLOR else ROW_COLOR,
                )
                return@Column
            }
            val address = running.addresses.firstOrNull()?.let { "$it:${running.port}" }
                ?: Localization.getString("devices_server_address_unknown").replace("%s", running.port.toString())
            InfoPill(Localization.getString("devices_server_address").replace("%s", address))
            running.addresses.drop(1).forEach { other ->
                Spacer(Modifier.height(8.dp))
                InfoPill(Localization.getString("devices_server_address_other").replace("%s", "$other:${running.port}"))
            }
            Spacer(Modifier.height(12.dp))
            InfoPill(
                Localization.getString("devices_code")
                    .replace("%s", "${running.verificationCode.take(3)} ${running.verificationCode.drop(3)}"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = Localization.getString("devices_code_hint"),
                color = Colors.SECONDARY.color,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            error?.let { InfoPill(it, background = FAILURE_COLOR) }
            Spacer(Modifier.height(12.dp))
            Text(
                text = Localization.getString("devices_server_running"),
                color = Colors.SECONDARY.color,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun InfoPill(text: String, background: Color = ROW_COLOR) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(background)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = text },
        )
    }

    @Composable
    private fun DeviceList(title: String, modifier: Modifier, content: LazyListScope.() -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.fillMaxSize().clip(LIST_SHAPE).background(Colors.SECONDARY.color).padding(10.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(text = title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(25.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(15.dp), modifier = Modifier.fillMaxSize(), content = content)
        }
    }

    @Composable
    private fun PendingRow(number: Int, request: PendingPairingRequest, onApprove: () -> Unit, onReject: () -> Unit) {
        DeviceRow("$number. ${request.surname} - ${platformName(request.platform)}", null) {
            ActionIcon(Localization.getString("devices_reject"), Res.drawable.cross_icon, size = 22, onClick = onReject)
            Spacer(Modifier.width(14.dp))
            ActionIcon(Localization.getString("devices_approve"), Res.drawable.check_icon, size = 30, onClick = onApprove)
        }
    }

    @Composable
    private fun PairedRow(number: Int, device: OperatorDevice, onRevoke: () -> Unit) {
        val connected = device.connectionState == DeviceConnectionState.CONNECTED
        val state = Localization.getString(if (connected) "devices_state_connected" else "devices_state_disconnected")
        DeviceRow("$number. ${device.surname} - ${platformName(device.platform)}", state, connected) {
            ActionIcon(Localization.getString("devices_revoke"), Res.drawable.cross_icon, size = 30, onClick = onRevoke)
        }
    }

    @Composable
    private fun DeviceRow(title: String, state: String?, connected: Boolean = false, actions: @Composable () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(ROW_COLOR)
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .semantics { contentDescription = listOfNotNull(title, state).joinToString(", ") },
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (state != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (connected) APPROVE_COLOR else Color.LightGray))
                        Spacer(Modifier.width(6.dp))
                        Text(text = state, color = Colors.SECONDARY.color, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            actions()
        }
    }

    /** Figma check or cross icon; the label names the action for screen readers. */
    @Composable
    private fun ActionIcon(label: String, icon: DrawableResource, size: Int, onClick: () -> Unit) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(size.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        )
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

    private val PANEL_SHAPE = RoundedCornerShape(15.dp)
    private val LIST_SHAPE = RoundedCornerShape(15.dp)
    private val ROW_SHAPE = RoundedCornerShape(8.dp)
    private val ROW_COLOR = Color(0xFF6A2BDD)
    private val PANEL_BORDER = Color(0xFF4B2A8F)
    private val APPROVE_COLOR = Color(0xFF3FD37B)
    private val FAILURE_COLOR = Color(0xFFB3261E)
}
