package org.mass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import org.mass.State.coroutinesScope
import org.mass.State.currentRoute
import org.mass.State.density
import org.mass.State.navController
import org.mass.enums.Routes
import org.mass.screens.DevicesConnectionScreen
import org.mass.screens.EntryScreen
import org.mass.ui.TypographyManager.getTypography
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.app_background

/**
 * Main fun, that render the whole application
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App() {
    navController = rememberNavController()
    coroutinesScope = rememberCoroutineScope()
    density = LocalDensity.current

    val currentBackStackEntry = navController!!.currentBackStackEntryAsState()
    currentBackStackEntry.value?.destination?.route?.let { route ->
        currentRoute = route
    }

    MaterialTheme(
        typography = getTypography()
    ) {
        CompositionLocalProvider(
            compositionLocalOf { State.currentLocale } provides State.currentLocale
        ) {
            Box(
                Modifier
                    .fillMaxSize(),
            ) {
                Image(
                    modifier = Modifier
                        .fillMaxSize(),
                    painter = painterResource(Res.drawable.app_background),
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        Modifier
                            .padding(10.dp)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        NavHost(
                            navController = navController!!,
                            startDestination = Routes.ENTRY.path,
                            contentAlignment = Alignment.Center,
                        ) {
                            animatedComposable(Routes.ENTRY) {
                                EntryScreen.Load()
                            }
                            animatedComposable(Routes.DEVICES_CONNECTION) {
                                DevicesConnectionScreen.Load()
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = false,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                    }
                }
            }
        }
    }
}

/**
 * Renders composable, usually screen instance, by specific route
 * @param route - [Routes] instance, on which content must be rendered
 * @param content - [Composable] fun, usually represents some screen, shown on specific [route]
 */
@OptIn(ExperimentalComposeUiApi::class)
fun NavGraphBuilder.animatedComposable(
    route: Routes,
    content: @Composable () -> Unit
) {
    composable(
        route = route.path,
        enterTransition = { fadeIn(tween(300)) },
        exitTransition = { fadeOut(tween(300)) },
        popEnterTransition = { fadeIn(tween(300)) },
        popExitTransition = { fadeOut(tween(300)) },
        content = {
            LaunchedEffect(State.isAnimating) {
                if (State.isAnimating) {
                    delay(400)
                    State.isAnimating = false
                }
            }
            content()
        }
    )
}