package com.eagertolisten.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.eagertolisten.app.data.model.Episode
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

object PlaybackController {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    fun initialize(context: Context) {
        if (mediaController != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Plays [episode]. Pass [startPositionMs] to start at a specific spot (e.g. a saved bookmark);
     * otherwise it resumes from the saved position. Seeking right after this call doesn't work,
     * because the service loads the episode asynchronously.
     */
    fun playEpisode(context: Context, episode: Episode, podcastId: String?, startPositionMs: Long? = null) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_EPISODE
            putExtra(PlaybackService.EXTRA_EPISODE_ID, episode.id)
            putExtra(PlaybackService.EXTRA_PODCAST_ID, podcastId)
            if (startPositionMs != null) putExtra(PlaybackService.EXTRA_START_POSITION_MS, startPositionMs)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun playFromSearch(context: Context, query: String?) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_FROM_SEARCH
            putExtra(PlaybackService.EXTRA_SEARCH_QUERY, query)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun togglePlayPause() {
        mediaController?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    fun seekBack() {
        mediaController?.seekBack() // back skip time from Settings (default 15s)
    }

    fun seekForward() {
        mediaController?.seekForward() // forward skip time from Settings (default 90s)
    }

    // The in-app skip buttons always change episodes. They use custom commands rather than
    // seekToNext()/seekToPrevious(), because those follow the car/headset button setting
    // (which defaults to jumping within the episode).
    fun skipToNext() {
        sendCommand(PlaybackService.CUSTOM_ACTION_SKIP_NEXT)
    }

    fun skipToPrevious() {
        sendCommand(PlaybackService.CUSTOM_ACTION_SKIP_PREVIOUS)
    }

    private fun sendCommand(action: String) {
        mediaController?.sendCustomCommand(
            androidx.media3.session.SessionCommand(action, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY
        )
    }

    fun saveInspiration(note: String = "") {
        mediaController?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.CUSTOM_ACTION_SAVE_INSPIRATION, android.os.Bundle().apply {
                putString("note", note)
            }),
            android.os.Bundle.EMPTY
        )
    }

    fun setSpeed(speed: Float) {
        mediaController?.playbackParameters = PlaybackParameters(speed)
        PlaybackStateManager.setSpeed(speed)
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        controllerFuture = null
    }
}
