package com.eagertolisten.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Next / Previous buttons on a car steering wheel, Bluetooth headset, Android Auto,
 * or the lock screen do. The in-app skip-episode buttons always skip episodes.
 */
enum class RemoteButtonAction {
    /** Next = jump forward by the forward skip time, Previous = jump back by the back skip time. */
    JUMP,
    /** Next = skip to the next episode, Previous = restart / go to the previous episode. */
    SKIP_EPISODE
}

data class PlayerSettingsState(
    val seekBackSeconds: Int = PlayerSettings.DEFAULT_SEEK_BACK_SECONDS,
    val seekForwardSeconds: Int = PlayerSettings.DEFAULT_SEEK_FORWARD_SECONDS,
    val remoteButtonAction: RemoteButtonAction = RemoteButtonAction.JUMP,
    val showBookmarkButton: Boolean = true
) {
    val seekBackMs: Long get() = seekBackSeconds * 1000L
    val seekForwardMs: Long get() = seekForwardSeconds * 1000L
}

/**
 * Player control preferences, shared by the UI and PlaybackService (same process).
 * Backed by the "eager_settings" SharedPreferences and exposed as a StateFlow so
 * open screens and the media session pick up changes immediately.
 */
object PlayerSettings {
    const val PREFS_NAME = "eager_settings"
    const val PREF_SEEK_BACK_SECONDS = "player_seek_back_seconds"
    const val PREF_SEEK_FORWARD_SECONDS = "player_seek_forward_seconds"
    const val PREF_REMOTE_BUTTON_ACTION = "player_remote_next_prev_action"
    const val PREF_SHOW_BOOKMARK_BUTTON = "player_show_bookmark_button"

    const val DEFAULT_SEEK_BACK_SECONDS = 15
    const val DEFAULT_SEEK_FORWARD_SECONDS = 90
    val SEEK_CHOICES_SECONDS: List<Int> = listOf(10, 15, 30, 45, 60, 90)

    private val _state = MutableStateFlow(PlayerSettingsState())
    val state: StateFlow<PlayerSettingsState> = _state.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Safe to call more than once; only the first call loads from disk. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _state.value = PlayerSettingsState(
                seekBackSeconds = sanitizeSeconds(p.getInt(PREF_SEEK_BACK_SECONDS, DEFAULT_SEEK_BACK_SECONDS), DEFAULT_SEEK_BACK_SECONDS),
                seekForwardSeconds = sanitizeSeconds(p.getInt(PREF_SEEK_FORWARD_SECONDS, DEFAULT_SEEK_FORWARD_SECONDS), DEFAULT_SEEK_FORWARD_SECONDS),
                remoteButtonAction = parseRemoteAction(p.getString(PREF_REMOTE_BUTTON_ACTION, null)),
                showBookmarkButton = p.getBoolean(PREF_SHOW_BOOKMARK_BUTTON, true)
            )
            prefs = p
        }
    }

    fun current(): PlayerSettingsState = _state.value

    fun setSeekBackSeconds(seconds: Int) {
        val value = sanitizeSeconds(seconds, DEFAULT_SEEK_BACK_SECONDS)
        _state.value = _state.value.copy(seekBackSeconds = value)
        prefs?.edit()?.putInt(PREF_SEEK_BACK_SECONDS, value)?.apply()
    }

    fun setSeekForwardSeconds(seconds: Int) {
        val value = sanitizeSeconds(seconds, DEFAULT_SEEK_FORWARD_SECONDS)
        _state.value = _state.value.copy(seekForwardSeconds = value)
        prefs?.edit()?.putInt(PREF_SEEK_FORWARD_SECONDS, value)?.apply()
    }

    fun setRemoteButtonAction(action: RemoteButtonAction) {
        _state.value = _state.value.copy(remoteButtonAction = action)
        prefs?.edit()?.putString(PREF_REMOTE_BUTTON_ACTION, action.name)?.apply()
    }

    fun setShowBookmarkButton(show: Boolean) {
        _state.value = _state.value.copy(showBookmarkButton = show)
        prefs?.edit()?.putBoolean(PREF_SHOW_BOOKMARK_BUTTON, show)?.apply()
    }

    /** Only the offered choices are accepted; anything else falls back to the default. */
    fun sanitizeSeconds(seconds: Int, fallback: Int): Int =
        if (seconds in SEEK_CHOICES_SECONDS) seconds else fallback

    fun parseRemoteAction(raw: String?): RemoteButtonAction =
        RemoteButtonAction.values().firstOrNull { it.name == raw } ?: RemoteButtonAction.JUMP
}
