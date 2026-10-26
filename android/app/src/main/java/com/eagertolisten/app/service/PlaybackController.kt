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

    fun playEpisode(context: Context, episode: Episode, podcastId: String?) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_EPISODE
            putExtra(PlaybackService.EXTRA_EPISODE_ID, episode.id)
            putExtra(PlaybackService.EXTRA_PODCAST_ID, podcastId)
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
        mediaController?.seekBack() // 15 seconds
    }

    fun seekForward() {
        mediaController?.seekForward() // 90 seconds
    }

    fun skipToNext() {
        mediaController?.seekToNext()
    }

    fun skipToPrevious() {
        mediaController?.seekToPrevious()
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
