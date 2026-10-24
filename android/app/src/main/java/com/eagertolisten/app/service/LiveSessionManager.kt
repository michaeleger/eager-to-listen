package com.eagertolisten.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.repository.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages live podcast listening sessions.
 * Rule: If restarting or returning to the app after not listening for >= 90 minutes while a live
 * show was tuned in, the app automatically reverts playback back to the top of the main programmed playlist.
 * (Thinking: when the live show you tuned into is over, it defaults back to your normal playlist).
 */
object LiveSessionManager {
    private const val TAG = "LiveSessionManager"
    private const val PREFS_NAME = "eager_live_session_prefs"

    private const val KEY_LAST_EPISODE_ID = "last_episode_id"
    private const val KEY_LAST_IS_LIVE = "last_is_live"
    private const val KEY_LAST_LISTENING_TIME = "last_listening_time"

    // 90 minutes inactivity threshold (in milliseconds)
    const val LIVE_INACTIVITY_TIMEOUT_MS = 90L * 60L * 1000L

    fun recordPlaybackActive(context: Context, episode: Episode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_EPISODE_ID, episode.id)
            .putBoolean(KEY_LAST_IS_LIVE, episode.isLive)
            .putLong(KEY_LAST_LISTENING_TIME, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Recorded playback episode: ${episode.title}, isLive=${episode.isLive}")
    }

    fun recordListeningHeartbeat(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_LISTENING_TIME, System.currentTimeMillis())
            .apply()
    }

    fun isLiveSessionExpired(
        prefs: android.content.SharedPreferences,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        val isLive = prefs.getBoolean(KEY_LAST_IS_LIVE, false)
        if (!isLive) return false

        val lastTime = prefs.getLong(KEY_LAST_LISTENING_TIME, 0L)
        if (lastTime <= 0L) return false

        val elapsed = currentTimeMs - lastTime
        val expired = elapsed >= LIVE_INACTIVITY_TIMEOUT_MS
        if (expired) {
            try {
                Log.i(TAG, "Live podcast session expired ($elapsed ms >= $LIVE_INACTIVITY_TIMEOUT_MS ms). Show is presumed over.")
            } catch (_: Throwable) {}
        }
        return expired
    }

    fun isLiveSessionExpired(context: Context, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return isLiveSessionExpired(prefs, currentTimeMs)
    }

    fun clearLiveSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LAST_IS_LIVE, false)
            .apply()
        Log.d(TAG, "Cleared live session flag")
    }

    suspend fun checkAndRevertIfExpired(context: Context, repository: PodcastRepository): Boolean = withContext(Dispatchers.IO) {
        if (!isLiveSessionExpired(context)) return@withContext false

        Log.i(TAG, "Auto-reverting from ended live podcast to main programmed playlist")
        clearLiveSession(context)

        val topEpisode = repository.getTopQueueEpisode()
        if (topEpisode != null) {
            val podcast = repository.getPodcastById(topEpisode.podcastId)
            val speed = podcast?.playbackSpeed ?: 1.0f
            val skipIntro = podcast?.skipIntroSeconds ?: 0
            val skipOutro = podcast?.skipOutroSeconds ?: 0

            withContext(Dispatchers.Main) {
                PlaybackStateManager.setEpisode(topEpisode, speed, skipIntro, skipOutro)
            }

            // Also signal PlaybackService to update its prepared media item without auto-playing
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_REVERT_TO_MAIN_QUEUE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not signal PlaybackService: ${e.message}")
            }
            return@withContext true
        }
        return@withContext false
    }
}
