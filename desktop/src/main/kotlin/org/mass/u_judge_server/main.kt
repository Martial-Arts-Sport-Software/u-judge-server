package org.mass.u_judge_server

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "u_judge_server",
    ) {
        App()
    }
}