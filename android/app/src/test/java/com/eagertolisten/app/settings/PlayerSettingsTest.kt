package com.eagertolisten.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSettingsTest {

    @Test
    fun defaultsMatchPrevious0_9_1Behavior() {
        val defaults = PlayerSettingsState()
        assertEquals(15, defaults.seekBackSeconds)
        assertEquals(90, defaults.seekForwardSeconds)
        assertEquals(15_000L, defaults.seekBackMs)
        assertEquals(90_000L, defaults.seekForwardMs)
        // Steering wheel / headset Next & Previous jump by default (the 0.9.1 regression fix).
        assertEquals(RemoteButtonAction.JUMP, defaults.remoteButtonAction)
        assertEquals(true, defaults.showBookmarkButton)
    }

    @Test
    fun sanitizeSecondsAcceptsOnlyOfferedChoices() {
        PlayerSettings.SEEK_CHOICES_SECONDS.forEach { secs ->
            assertEquals(secs, PlayerSettings.sanitizeSeconds(secs, fallback = 15))
        }
        assertEquals(15, PlayerSettings.sanitizeSeconds(0, fallback = 15))
        assertEquals(90, PlayerSettings.sanitizeSeconds(-30, fallback = 90))
        assertEquals(90, PlayerSettings.sanitizeSeconds(120, fallback = 90))
    }

    @Test
    fun parseRemoteActionFallsBackToJump() {
        assertEquals(RemoteButtonAction.JUMP, PlayerSettings.parseRemoteAction(null))
        assertEquals(RemoteButtonAction.JUMP, PlayerSettings.parseRemoteAction(""))
        assertEquals(RemoteButtonAction.JUMP, PlayerSettings.parseRemoteAction("garbage"))
        assertEquals(RemoteButtonAction.JUMP, PlayerSettings.parseRemoteAction("JUMP"))
        assertEquals(RemoteButtonAction.SKIP_EPISODE, PlayerSettings.parseRemoteAction("SKIP_EPISODE"))
    }
}
