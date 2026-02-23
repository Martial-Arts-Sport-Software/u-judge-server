package org.mass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import java.util.Locale

object State {
    var judgeSurname by mutableStateOf("")
    var navController: NavHostController? = null
    var density: Density? = null
    var currentLocale: String by mutableStateOf(Locale.getDefault().language)
    var isAnimating by mutableStateOf(false)
    var currentError by mutableStateOf("")
}