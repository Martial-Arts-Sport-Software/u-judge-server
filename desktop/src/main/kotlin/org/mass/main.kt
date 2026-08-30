package org.mass

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowDecorationDefaults
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.mass.State.currentRoute
import org.mass.locale.Localization

fun main() = application {
    val windowState = rememberWindowState(
        width = 1280.dp,
        height = 720.dp
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "U'Judge - Server - ${Localization.getString(currentRoute)}",
        state = windowState,
    ) {
        App()
    }
}