package com.eagertolisten.app.service

import androidx.media3.common.PlaybackException

enum class SeekForwardResult {
    SEEK,
    FINISH_EPISODE
}

enum class DiscontinuityAction {
    NONE,
    FINISH_EPISODE,
    SAVE_POSITION_LEFT
}

enum class ErrorResolution {
    RETRY_NETWORK,
    RETRY_AUDIO_DEVICE,
    MAX_RETRIES_REACHED,
    DISMISS_UNPLAYABLE
}

class PlaybackCoordinator(
    private val onEpisodeFinished: (episodeId: String) -> Unit,
    private val onEpisodeSkipped: (episodeId: String, positionMs: Long) -> Unit,
    private val onEpisodeLeft: (episodeId: String, positionMs: Long) -> Unit
) {
    var isSkipInProgress: Boolean = false
        private set

    private var lastSkipTimestamp: Long = 0L

    fun shouldDebounceSkip(nowMs: Long): Boolean {
        if (nowMs - lastSkipTimestamp < 1000L) {
            return true
        }
        lastSkipTimestamp = nowMs
        return false
    }

    fun onExplicitSkip(
        episodeId: String,
        currentPosMs: Long,
        durationMs: Long,
        skipOutroSeconds: Int,
        nowMs: Long
    ): Boolean {
        if (shouldDebounceSkip(nowMs)) {
            return false
        }
        isSkipInProgress = true
        val skipOutroMs = skipOutroSeconds * 1000L
        val isNearEnd = (durationMs > 30_000L && currentPosMs >= (durationMs * 0.98)) ||
                (skipOutroMs > 0 && durationMs > skipOutroMs + 15_000L && (durationMs - currentPosMs) <= skipOutroMs)

        if (isNearEnd) {
            onEpisodeFinished(episodeId)
        } else {
            onEpisodeSkipped(episodeId, currentPosMs)
        }
        return true
    }

    fun calculateSeekForward(
        currentPosMs: Long,
        durationMs: Long,
        skipOutroSeconds: Int,
        incrementMs: Long = PlaybackConfig.SEEK_FORWARD_INCREMENT_MS
    ): Pair<SeekForwardResult, Long> {
        val skipOutroMs = skipOutroSeconds * 1000L
        val effectiveEndMs = if (durationMs > 0 && skipOutroMs > 0 && durationMs > skipOutroMs + 15_000L) {
            durationMs - skipOutroMs
        } else {
            durationMs
        }

        return if (effectiveEndMs > 0 && (currentPosMs + incrementMs >= effectiveEndMs - 1500L)) {
            SeekForwardResult.FINISH_EPISODE to effectiveEndMs
        } else {
            val targetPos = if (durationMs > 0) minOf(currentPosMs + incrementMs, durationMs) else (currentPosMs + incrementMs)
            SeekForwardResult.SEEK to targetPos
        }
    }

    fun calculateSeekBack(
        currentPosMs: Long,
        skipIntroSeconds: Int,
        decrementMs: Long = PlaybackConfig.SEEK_BACK_INCREMENT_MS
    ): Long {
        val skipIntroMs = skipIntroSeconds * 1000L
        val minPos = if (skipIntroMs > 0 && currentPosMs > skipIntroMs) skipIntroMs else 0L
        return maxOf(minPos, currentPosMs - decrementMs)
    }

    fun onPositionDiscontinuity(
        oldEpisodeId: String?,
        oldPosMs: Long,
        newEpisodeId: String?,
        isAutoTransition: Boolean,
        isSeek: Boolean
    ): DiscontinuityAction {
        if (isAutoTransition) {
            if (oldEpisodeId != null) {
                onEpisodeFinished(oldEpisodeId)
                return DiscontinuityAction.FINISH_EPISODE
            }
        } else if (isSeek) {
            if (isSkipInProgress) {
                isSkipInProgress = false
            } else if (oldEpisodeId != null && oldEpisodeId != newEpisodeId) {
                onEpisodeLeft(oldEpisodeId, oldPosMs)
                return DiscontinuityAction.SAVE_POSITION_LEFT
            }
        }
        return DiscontinuityAction.NONE
    }

    /**
     * Only errors that are known to be permanent for this enclosure may dismiss an episode.
     * Audio output and decoder-init failures follow audio focus and route changes (phone calls,
     * navigation prompts, Bluetooth / car head unit switching) and recover once the audio device
     * is available again. Unknown errors are retried, matching the original always-retry behavior.
     */
    fun classifyError(errorCode: Int, retryCount: Int, httpStatus: Int? = null): ErrorResolution {
        return when {
            isAudioDeviceError(errorCode) ->
                if (retryCount < MAX_AUDIO_DEVICE_RETRIES) ErrorResolution.RETRY_AUDIO_DEVICE else ErrorResolution.MAX_RETRIES_REACHED
            isPermanentError(errorCode, httpStatus) -> ErrorResolution.DISMISS_UNPLAYABLE
            else ->
                if (retryCount < MAX_NETWORK_RETRIES) ErrorResolution.RETRY_NETWORK else ErrorResolution.MAX_RETRIES_REACHED
        }
    }

    /** Backoff before retry number [retryCount] (1-based) for the given resolution. */
    fun retryDelayMs(resolution: ErrorResolution, retryCount: Int): Long {
        val attempt = retryCount.coerceAtLeast(1)
        return when (resolution) {
            ErrorResolution.RETRY_AUDIO_DEVICE -> minOf(attempt * 1_000L, 5_000L)
            ErrorResolution.RETRY_NETWORK -> minOf(1_000L shl minOf(attempt, 5), 30_000L)
            else -> 0L
        }
    }

    private fun isAudioDeviceError(errorCode: Int): Boolean {
        // 5xxx = audio sink / AudioTrack errors (init, write, offload)
        return errorCode in 5000..5999 ||
                errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
    }

    private fun isPermanentError(errorCode: Int, httpStatus: Int?): Boolean {
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                httpStatus == null || (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429)
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            else -> false
        }
    }

    companion object {
        const val MAX_NETWORK_RETRIES = 20      // ~8.5 minutes of backoff: rides out tunnels and dead zones
        const val MAX_AUDIO_DEVICE_RETRIES = 10 // ~40 seconds: covers calls ending and Bluetooth reconnects
    }

    fun resetSkipFlag() {
        isSkipInProgress = false
    }
}
