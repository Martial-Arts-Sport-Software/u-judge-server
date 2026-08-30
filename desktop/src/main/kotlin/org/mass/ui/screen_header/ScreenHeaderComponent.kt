package org.mass.ui.screen_header

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mass.State.currentRoute
import org.mass.State.isAnimating
import org.mass.enums.Routes
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.button.ButtonStyles
import org.mass.ui.button.clickWithTransition
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.back_icon
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ScreenHeaderComponent(
    modifier: Modifier,
) {
    var displayedTitle by remember { mutableStateOf(currentRoute) }

    LaunchedEffect(currentRoute) {
        isAnimating = true
        delay(250.milliseconds)
        displayedTitle = currentRoute
        delay(150.milliseconds)
        isAnimating = false
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        val goBackOnclick = remember { {
            clickWithTransition(Routes.BACK)
        } }
        ButtonComponent(
            style = ButtonStyles.Icon,
            iconSrc = Res.drawable.back_icon,
            onclick = goBackOnclick,
            modifier = Modifier.height(50.dp)
        )
        Spacer(Modifier.weight(0.8f))
        Text(
            text = Localization.getString(displayedTitle),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.weight(1f))
    }
}
