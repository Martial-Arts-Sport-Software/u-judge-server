package org.mass

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mass.State.currentRoute
import org.mass.locale.Localization
import org.mass.screens.SpectatorWindow
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // `--smoke-test` checks the installed server headlessly, without a window (installer workflow, I7).
    ServerSmokeCheck.options(args)?.let { exitProcess(ServerSmokeCheck.run(it)) }
    ServerLog.useDirectory(ServerRuntimeConfiguration.fromEnvironment().applicationDataDirectory.resolve("logs"))
    desktopApplication()
}

private fun desktopApplication() = application {
    LaunchedEffect(Unit) {
        Runtime.getRuntime().addShutdownHook(Thread(Server::stop))
        withContext(Dispatchers.IO) { Server.start() }
    }
    val windowState = rememberWindowState(
        width = 1280.dp,
        height = 720.dp
    )

    Window(
        onCloseRequest = {
            Server.stop()
            exitApplication()
        },
        title = "U'Judge - Server - ${Localization.getString(currentRoute)}",
        state = windowState,
    ) {
        App()
    }
    // The spectator screen for the external monitor (ADR-010 §8), opened from the bout screen.
    if (State.spectatorOpen) {
        SpectatorWindow(onClose = { State.spectatorOpen = false })
    }
}