package org.mass.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.Font
import u_judge_server.desktop.generated.resources.Res
import u_judge_server.desktop.generated.resources.Montserrat
import org.mass.enums.Colors

object TypographyManager {
    @Composable
    fun getTypography(): Typography {
        val montserratVariable = FontFamily(
            Font(
                resource = Res.font.Montserrat,
                weight = FontWeight.SemiBold,
                style = FontStyle.Normal
            )
        )

        return Typography(
            titleLarge = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 3.em,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 1.em,
            ),
            titleMedium = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 1.6.em,
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            titleSmall = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 1.em,
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            bodyLarge = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 0.9.em,
                color = Color.White
            ),
            bodyMedium = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 0.7.em,
                color = Color.White
            ),
            labelLarge = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 0.8.em,
            ),
            displayLarge = TextStyle(
                fontFamily = montserratVariable,
                fontSize = 2.em,
                color = Colors.PRIMARY.color
            )
        )
    }
}