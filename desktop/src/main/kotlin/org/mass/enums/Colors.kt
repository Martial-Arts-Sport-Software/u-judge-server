package org.mass.enums

import androidx.compose.ui.graphics.Color

/**
 * UI colors
 * @property PRIMARY
 * @property SECONDARY
 * @property SLIDER_TRACK_ACTIVE
 * @property BUTTON_BLUE
 * @property BUTTON_RED
 * @property BUTTON_GRAY
 * @property BUTTON_BROWN
 * @property BUTTON_GREEN
 */
enum class Colors(val color: Color) {
    PRIMARY(Color(0xFF7C45E2)),
    SECONDARY(Color(0xFFEFD4FF)),
    SLIDER_TRACK_ACTIVE(Color(0xFFBC9DF6)),
    BUTTON_BLUE(Color(0xBF5500FF)),
    BUTTON_RED(Color(0xBFBB0042)),
    BUTTON_GRAY(Color(0xBF525151)),
    BUTTON_BROWN(Color(0xFF2C2C2C)),
    BUTTON_GREEN(Color(0xFF02DC60)),
}