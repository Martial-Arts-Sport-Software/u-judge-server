package org.mass.enums

import androidx.compose.ui.graphics.Color

/** UI colors. */
enum class Colors(val color: Color) {
    PRIMARY(Color(0xFF7C45E2)),
    SECONDARY(Color(0xFFEFD4FF)),
    GRAY(Color(0xBF525151)),
    BROWN(Color(0xFF2C2C2C)),
    // The sides of a bout, as on the client: the upper entry of a pair is blue, the lower one red.
    BLUE(Color(0xFF5500FF)),
    RED(Color(0xFFBB0042)),
}
