package com.eagertolisten.app.service

/**
 * Factory defaults for the skip times. The live values come from
 * [com.eagertolisten.app.settings.PlayerSettings], which the user can change in Settings.
 */
object PlaybackConfig {
    const val SEEK_BACK_INCREMENT_MS = 15_000L
    const val SEEK_FORWARD_INCREMENT_MS = 90_000L
}
