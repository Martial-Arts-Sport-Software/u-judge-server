package org.mass.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import java.net.InetAddress
import u_judge_server.desktop.generated.resources.empty_available_devices
import u_judge_server.desktop.generated.resources.empty_connected_devices
import u_judge_server.desktop.generated.resources.server_computer
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
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
                        .padding(25.dp),
                ) {
                    val paired = registry.devices.filterNot(OperatorDevice::revoked)
                    DeviceList(
                        Localization.getString("devices_connection_connected"),
                        Modifier.weight(1f),
                        empty = paired.isEmpty(),
                        emptyState = { EmptyState(Res.drawable.empty_connected_devices, "devices_paired_empty", "devices_paired_empty_hint") },
                    ) {
                        itemsIndexed(paired, key = { _, device -> device.requestId }) { index, device ->
                            Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = tween(300), fadeOutSpec = tween(300))) {
                                PairedRow(index + 1, device, onRevoke = { revokeCandidate = device })
                            }
                        }
                    }
                    Spacer(Modifier.width(25.dp))
                    DeviceList(
                        Localization.getString("devices_connection_available"),
                        Modifier.weight(1f),
                        empty = registry.pending.isEmpty(),
                        emptyState = { EmptyState(Res.drawable.empty_available_devices, "devices_pending_empty", "devices_pending_empty_hint") },
                    ) {
                        itemsIndexed(registry.pending, key = { _, request -> request.requestId }) { index, request ->
                            Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = tween(300), fadeOutSpec = tween(300))) {
                                PendingRow(
                                    index + 1,
                                    request,
                                    onApprove = { decide { approve(request.requestId) } },
                                    onReject = { decide { reject(request.requestId) } },
                                )
                            }
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

    /** "Конфигурация устройств": this computer, where judges connect, the code they must see and component health. */
    @Composable
    private fun ServerPanel(state: ServerRuntimeState, error: String?, modifier: Modifier) {
        val running = state as? ServerRuntimeState.Running
        val host = remember { hostDescription() }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier
                .fillMaxHeight()
                .clip(PANEL_SHAPE)
                .background(PANEL_DARK)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = Localization.getString("devices_config_title"),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            HostCard(host)
            if (running != null) {
                AddressPill(running)
                CodeCard(running.verificationCode)
            } else {
                InfoPill(Localization.getString("devices_code_unavailable"))
            }
            StatusItems(state)
            error?.let { InfoPill(it, background = FAILURE_COLOR) }
        }
    }

    @Composable
    private fun HostCard(host: HostDescription) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CARD_SHAPE)
                .background(CARD_COLOR)
                .border(1.dp, CARD_BORDER, CARD_SHAPE)
                .padding(vertical = 18.dp, horizontal = 12.dp),
        ) {
            Image(painterResource(Res.drawable.server_computer), contentDescription = null, modifier = Modifier.size(84.dp))
            Spacer(Modifier.height(10.dp))
            Text(host.name, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(host.system, color = MUTED_TEXT, style = MaterialTheme.typography.bodySmall)
        }
    }

    /** The main address judges type in; a "+N" chip shows the other interfaces of this computer on hover. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AddressPill(running: ServerRuntimeState.Running) {
        val primary = running.addresses.firstOrNull()?.let { "$it:${running.port}" }
        val others = running.addresses.drop(1).map { "$it:${running.port}" }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CARD_SHAPE)
                .background(ROW_COLOR)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            if (primary == null) {
                Text(
                    Localization.getString("devices_server_address_unknown").replace("%s", running.port.toString()),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    Localization.getString("devices_server_address_label"),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    primary,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (others.isNotEmpty()) {
                TooltipArea(
                    tooltip = {
                        Column(
                            Modifier.clip(ROW_SHAPE).background(Colors.SECONDARY.color).padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                Localization.getString("devices_server_address_other"),
                                color = Colors.PRIMARY.color,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            others.forEach {
                                Text(it, color = Colors.PRIMARY.color, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            }
                        }
                    },
                    tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
                ) {
                    Text(
                        "+${others.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = others.joinToString() },
                    )
                }
            }
        }
    }

    /** The verification code judges compare before approval, in one line. */
    @Composable
    private fun CodeCard(code: String) {
        val formatted = "${code.take(3)} ${code.drop(3)}"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CODE_CARD_COLOR)
                .padding(vertical = 20.dp, horizontal = 16.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${Localization.getString("devices_code_label")} $formatted"
                },
        ) {
            Text(
                text = Localization.getString("devices_code_label").uppercase(),
                color = LAVENDER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatted,
                color = Color.White,
                fontSize = 46.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = Localization.getString("devices_code_hint"),
                color = LAVENDER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }

    /** One card per component: a check when it works, an amber dot while starting, a cross after a failure. */
    @Composable
    private fun StatusItems(state: ServerRuntimeState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("devices_status_server", "devices_status_database", "devices_status_tls").forEach { key ->
                val text = Localization.getString(key)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CARD_COLOR)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) { contentDescription = text },
                ) {
                    StatusMark(state)
                    Spacer(Modifier.width(12.dp))
                    Text(text, color = Color(0xFFE4E1EA), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (state is ServerRuntimeState.Failed) {
                Text(Localization.getString("devices_server_failed"), color = MUTED_TEXT, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun StatusMark(state: ServerRuntimeState) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp).clip(RoundedCornerShape(50))) {
            when (state) {
                is ServerRuntimeState.Running -> {
                    Box(Modifier.matchParentSize().background(CHECK_BACKGROUND))
                    Image(painterResource(Res.drawable.check_icon), contentDescription = null, modifier = Modifier.size(14.dp))
                }
                ServerRuntimeState.Starting -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(STARTING_COLOR))
                else -> {
                    Box(Modifier.matchParentSize().background(FAILURE_COLOR.copy(alpha = 0.25f)))
                    Image(painterResource(Res.drawable.cross_icon), contentDescription = null, modifier = Modifier.size(12.dp))
                }
            }
        }
    }

    private data class HostDescription(val name: String, val system: String)

    private fun hostDescription(): HostDescription {
        val name = runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrDefault("")
            .let { if (it == it.uppercase()) it.lowercase().replaceFirstChar(Char::uppercase) else it }
            .ifBlank { Localization.getString("devices_host_unknown") }
        val system = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${System.getProperty("os.arch")}"
        return HostDescription(name, system)
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

    /** A titled list; when [empty], [emptyState] is centered in the remaining height instead of the rows. */
    @Composable
    private fun DeviceList(
        title: String,
        modifier: Modifier,
        empty: Boolean,
        emptyState: @Composable () -> Unit,
        content: LazyListScope.() -> Unit,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.fillMaxSize().clip(LIST_SHAPE).background(Colors.SECONDARY.color).padding(10.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(text = title, color = Colors.PRIMARY.color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(25.dp))
            // The empty illustration and the rows cross-fade, like the popups of the app.
            AnimatedContent(
                targetState = empty,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                modifier = Modifier.fillMaxSize(),
            ) { isEmpty ->
                if (isEmpty) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { emptyState() }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(15.dp), modifier = Modifier.fillMaxSize(), content = content)
                }
            }
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

    /** Illustrated empty list: what is missing and what to do about it. */
    @Composable
    private fun EmptyState(image: DrawableResource, titleKey: String, hintKey: String) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Image(painterResource(image), contentDescription = null, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = Localization.getString(titleKey),
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Localization.getString(hintKey),
                color = Colors.PRIMARY.color.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
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
    private val APPROVE_COLOR = Color(0xFF3FD37B)
    private val FAILURE_COLOR = Color(0xFFB3261E)
    private val STARTING_COLOR = Color(0xFFF2B233)
    private val CARD_SHAPE = RoundedCornerShape(16.dp)
    private val PANEL_DARK = Color(0xFF1F1E24)
    private val CARD_COLOR = Color(0xFF27262D)
    private val CARD_BORDER = Color(0xFF34333B)
    private val CODE_CARD_COLOR = Color(0xFF5A12D4)
    private val LAVENDER_TEXT = Color(0xFFC9B6F2)
    private val MUTED_TEXT = Color(0xFF9E9AA7)
    private val CHECK_BACKGROUND = Color(0xFF1F3B2C)
}
