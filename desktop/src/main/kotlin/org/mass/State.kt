package org.mass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import org.mass.enums.Routes

object State {
    var judgeSurname by mutableStateOf("")
    var navController: NavHostController? = null
    var density: Density? = null
    var coroutinesScope: CoroutineScope? = null
    /** v1 Pilot starts in Russian whatever the OS language; English is switchable on the entry screen (ADR-012 §8). */
    var currentLocale: String by mutableStateOf("ru")
    var isAnimating by mutableStateOf(false)
    var currentError by mutableStateOf("")
    var currentRoute by mutableStateOf(Routes.ENTRY.path)

    /** The spectator window on the external monitor (ADR-010 §8). */
    var spectatorOpen by mutableStateOf(false)

}
