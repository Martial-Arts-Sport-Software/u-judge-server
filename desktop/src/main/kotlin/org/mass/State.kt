package org.mass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import com.appstractive.dnssd.DiscoveredService
import kotlinx.coroutines.CoroutineScope
import org.mass.enums.Routes
import java.util.Locale

object State {
    var judgeSurname by mutableStateOf("")
    var navController: NavHostController? = null
    var density: Density? = null
    var coroutinesScope: CoroutineScope? = null
    var currentLocale: String by mutableStateOf(Locale.getDefault().language)
    var isAnimating by mutableStateOf(false)
    var currentError by mutableStateOf("")
    var currentRoute by mutableStateOf(Routes.ENTRY.path)

    var availableDevices = mutableStateMapOf<String, DiscoveredService>()
    var connectedDevices = mutableStateMapOf<String, DiscoveredService>()
}