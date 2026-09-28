package org.mass.enums
import org.mass.screens.Screen

/**
 * App routes, for each of them there is specific [Screen] instance
 */
enum class Routes(val path: String) {
    ENTRY("entry"),
    DEVICES_CONNECTION("devices_connection"),
    BRACKETS_SETUP("brackets_setup"),

    BACK("");

    override fun toString(): String = path
}