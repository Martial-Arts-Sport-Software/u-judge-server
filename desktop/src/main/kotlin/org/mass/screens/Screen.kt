package org.mass.screens

import androidx.compose.runtime.Composable

/**
 * Main screen interface
 * @property Load function with [Composable] annotation, that loads the screen
 */
interface Screen {
    /**
     * Function for drawing screen's UI
     */
    @Composable
    fun Load()
}