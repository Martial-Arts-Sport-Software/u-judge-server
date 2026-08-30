package org.mass.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mass.Server
import org.mass.State.coroutinesScope
import org.mass.enums.Colors
import org.mass.locale.Localization
import org.mass.ui.button.ButtonComponent
import org.mass.ui.screen_header.ScreenHeaderComponent

object DevicesConnectionScreen : Screen {
    @Composable
    override fun Load() {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeaderComponent(
                modifier = Modifier
                    .fillMaxHeight(0.08f)
                    .fillMaxWidth()
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(
                        vertical = 10.dp,
                        horizontal = 15.dp,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Colors.GRAY.color)
                        .padding(25.dp),
                ) {
                    DevicesList(
                        modifier = Modifier
                            .weight(1f),
                        title = Localization.getString("devices_connection_connected"),
                    )
                    Spacer(Modifier.width(25.dp))
                    DevicesList(
                        modifier = Modifier
                            .weight(1f),
                        title = Localization.getString("devices_connection_available"),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Colors.GRAY.color)
                        .padding(25.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(Colors.SECONDARY.color)
                            .padding(15.dp),
                    ) {
                        ButtonComponent(
                            modifier = Modifier.fillMaxWidth(1f),
                            text = "Start scan",
                            onclick = {
                                coroutinesScope?.launch {
                                    Server.stop()
                                    Server.start()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DevicesList(
        title: String,
        modifier: Modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(15.dp))
                .background(Colors.SECONDARY.color)
                .padding(15.dp)
        ) {
            Text(
                text = title,
                color = Colors.PRIMARY.color,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}