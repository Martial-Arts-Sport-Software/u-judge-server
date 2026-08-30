package org.mass.enums

import androidx.compose.ui.graphics.Color

/**
 * UI colors
 * @property PRIMARY
 * @property SECONDARY
 * @property SLIDER_TRACK_ACTIVE
 * @property BLUE
 * @property RED
 * @property GRAY
 * @property BROWN
 * @property GREEN
 */
enum class Colors(val color: Color) {
    PRIMARY(Color(0xFF7C45E2)),
    SECONDARY(Color(0xFFEFD4FF)),
    SLIDER_TRACK_ACTIVE(Color(0xFFBC9DF6)),
    BLUE(Color(0xBF5500FF)),
    RED(Color(0xBFBB0042)),
    GRAY(Color(0xBF525151)),
    BROWN(Color(0xFF2C2C2C)),
    GREEN(Color(0xFF02DC60)),
}