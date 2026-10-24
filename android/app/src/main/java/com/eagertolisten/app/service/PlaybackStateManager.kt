package com.eagertolisten.app.service

import com.eagertolisten.app.data.model.Episode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val currentEpisode: Episode? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false
)

object PlaybackStateManager {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun update(transform: (PlaybackState) -> PlaybackState) {
        _state.value = transform(_state.value)
    }

    fun setEpisode(
        episode: Episode?,
        speed: Float = 1.0f,
        skipIntro: Int = 0,
        skipOutro: Int = 0,
        isPlaying: Boolean = false
    ) {
        _state.value = _state.value.copy(
            currentEpisode = episode,
            currentPositionMs = episode?.playbackPositionSeconds?.times(1000L) ?: 0L,
            durationMs = episode?.durationSeconds?.times(1000L) ?: 0L,
            playbackSpeed = speed,
            skipIntroSeconds = skipIntro,
            skipOutroSeconds = skipOutro,
            isPlaying = isPlaying,
            isLive = episode?.isLive ?: false
        )
    }

    fun setPlaying(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }

    fun setProgress(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        _state.value = _state.value.copy(
            currentPositionMs = positionMs,
            durationMs = if (durationMs > 0) durationMs else _state.value.durationMs,
            bufferedPositionMs = bufferedMs
        )
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    fun setBuffering(isBuffering: Boolean) {
        _state.value = _state.value.copy(isBuffering = isBuffering)
    }
}
