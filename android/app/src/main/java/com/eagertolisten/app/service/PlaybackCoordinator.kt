package com.eagertolisten.app.service

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
    MAX_NETWORK_RETRIES_REACHED,
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

    fun classifyError(errorCode: Int, retryCount: Int): ErrorResolution {
        val isNetwork = errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED

        return if (isNetwork) {
            if (retryCount < 5) ErrorResolution.RETRY_NETWORK else ErrorResolution.MAX_NETWORK_RETRIES_REACHED
        } else {
            ErrorResolution.DISMISS_UNPLAYABLE
        }
    }

    fun resetSkipFlag() {
        isSkipInProgress = false
    }
}
