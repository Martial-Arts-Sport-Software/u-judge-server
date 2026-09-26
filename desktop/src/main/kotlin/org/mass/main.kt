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

fun main() {
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
}