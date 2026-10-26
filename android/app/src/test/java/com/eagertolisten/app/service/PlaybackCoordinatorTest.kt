package com.eagertolisten.app.service

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackCoordinatorTest {

    private val finishedEpisodes = mutableListOf<String>()
    private val skippedEpisodes = mutableListOf<Pair<String, Long>>()
    private val leftEpisodes = mutableListOf<Pair<String, Long>>()

    private lateinit var coordinator: PlaybackCoordinator

    @Before
    fun setUp() {
        finishedEpisodes.clear()
        skippedEpisodes.clear()
        leftEpisodes.clear()
        coordinator = PlaybackCoordinator(
            onEpisodeFinished = { finishedEpisodes.add(it) },
            onEpisodeSkipped = { epId, pos -> skippedEpisodes.add(epId to pos) },
            onEpisodeLeft = { epId, pos -> leftEpisodes.add(epId to pos) }
        )
    }

    @Test
    fun autoTransition_marksPreviousEpisodeFinished() {
        val action = coordinator.onPositionDiscontinuity(
            oldEpisodeId = "ep_1",
            oldPosMs = 1800_000L,
            newEpisodeId = "ep_2",
            isAutoTransition = true,
            isSeek = false
        )

        assertEquals(DiscontinuityAction.FINISH_EPISODE, action)
        assertEquals(listOf("ep_1"), finishedEpisodes)
        assertTrue(skippedEpisodes.isEmpty())
        assertTrue(leftEpisodes.isEmpty())
    }

    @Test
    fun seekToAnotherEpisode_savesPositionOfLeftEpisode() {
        val action = coordinator.onPositionDiscontinuity(
            oldEpisodeId = "ep_1",
            oldPosMs = 1200_000L, // 20:00
            newEpisodeId = "ep_2",
            isAutoTransition = false,
            isSeek = true
        )

        assertEquals(DiscontinuityAction.SAVE_POSITION_LEFT, action)
        assertTrue(finishedEpisodes.isEmpty())
        assertTrue(skippedEpisodes.isEmpty())
        assertEquals(listOf("ep_1" to 1200_000L), leftEpisodes)
    }

    @Test
    fun explicitSkip_midEpisode_recordsSkipAndPosition() {
        // Skip at 12:00 of 60:00 episode
        val durationMs = 3600_000L
        val currentPosMs = 720_000L
        val success = coordinator.onExplicitSkip(
            episodeId = "ep_1",
            currentPosMs = currentPosMs,
            durationMs = durationMs,
            skipOutroSeconds = 0,
            nowMs = 1000L
        )

        assertTrue(success)
        assertTrue(finishedEpisodes.isEmpty())
        assertEquals(listOf("ep_1" to 720_000L), skippedEpisodes)
        assertTrue(coordinator.isSkipInProgress)

        // Following seek discontinuity consumes the skipInProgress flag without calling onEpisodeLeft
        val action = coordinator.onPositionDiscontinuity(
            oldEpisodeId = "ep_1",
            oldPosMs = currentPosMs,
            newEpisodeId = "ep_2",
            isAutoTransition = false,
            isSeek = true
        )

        assertEquals(DiscontinuityAction.NONE, action)
        assertFalse(coordinator.isSkipInProgress)
        assertTrue(leftEpisodes.isEmpty())
    }

    @Test
    fun explicitSkip_nearEnd_marksEpisodeFinished() {
        // Skip at 59:00 of 60:00 episode (> 98%)
        val durationMs = 3600_000L
        val currentPosMs = 3540_000L
        val success = coordinator.onExplicitSkip(
            episodeId = "ep_1",
            currentPosMs = currentPosMs,
            durationMs = durationMs,
            skipOutroSeconds = 0,
            nowMs = 1000L
        )

        assertTrue(success)
        assertEquals(listOf("ep_1"), finishedEpisodes)
        assertTrue(skippedEpisodes.isEmpty())
    }

    @Test
    fun explicitSkip_debouncedWithinOneSecond() {
        val success1 = coordinator.onExplicitSkip(
            episodeId = "ep_1",
            currentPosMs = 300_000L,
            durationMs = 1800_000L,
            skipOutroSeconds = 0,
            nowMs = 1000L
        )
        val success2 = coordinator.onExplicitSkip(
            episodeId = "ep_2",
            currentPosMs = 1000L,
            durationMs = 1800_000L,
            skipOutroSeconds = 0,
            nowMs = 1500L // 500ms later -> debounced
        )
        val success3 = coordinator.onExplicitSkip(
            episodeId = "ep_2",
            currentPosMs = 1000L,
            durationMs = 1800_000L,
            skipOutroSeconds = 0,
            nowMs = 2100L // 1100ms later -> accepted
        )

        assertTrue(success1)
        assertFalse(success2)
        assertTrue(success3)
    }

    @Test
    fun seekForward_withinEpisode_returnsTargetPosition() {
        // At 10:00 of 60:00 episode, +90s jump
        val (result, targetPos) = coordinator.calculateSeekForward(
            currentPosMs = 600_000L,
            durationMs = 3600_000L,
            skipOutroSeconds = 0,
            incrementMs = 90_000L
        )

        assertEquals(SeekForwardResult.SEEK, result)
        assertEquals(690_000L, targetPos)
    }

    @Test
    fun seekForward_crossingEnd_finishesEpisode() {
        // At 58:45 (3525s) of 60:00 (3600s), +90s jump reaches 3615s >= 3600s - 1.5s
        val (result, _) = coordinator.calculateSeekForward(
            currentPosMs = 3525_000L,
            durationMs = 3600_000L,
            skipOutroSeconds = 0,
            incrementMs = 90_000L
        )

        assertEquals(SeekForwardResult.FINISH_EPISODE, result)
    }

    @Test
    fun seekForward_reachingOutro_finishesEpisode() {
        // At 58:00 (3480s) of 60:00 (3600s), skipOutro = 45s (effectiveEnd = 3555s)
        // 3480 + 90 = 3570s >= 3555s - 1.5s -> finishes
        val (result, _) = coordinator.calculateSeekForward(
            currentPosMs = 3480_000L,
            durationMs = 3600_000L,
            skipOutroSeconds = 45,
            incrementMs = 90_000L
        )

        assertEquals(SeekForwardResult.FINISH_EPISODE, result)
    }

    @Test
    fun seekBack_respectsSkipIntroClamp() {
        // Current: 35s, skipIntro: 30s. Rewind 15s -> clamped to 30s
        val targetPos = coordinator.calculateSeekBack(
            currentPosMs = 35_000L,
            skipIntroSeconds = 30,
            decrementMs = 15_000L
        )
        assertEquals(30_000L, targetPos)

        // Current: 50s, skipIntro: 30s. Rewind 15s -> 35s
        val targetPos2 = coordinator.calculateSeekBack(
            currentPosMs = 50_000L,
            skipIntroSeconds = 30,
            decrementMs = 15_000L
        )
        assertEquals(35_000L, targetPos2)
    }

    @Test
    fun classifyError_networkAndNonNetwork() {
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 0)
        )
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 4)
        )
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackCoordinator.MAX_NETWORK_RETRIES - 1)
        )
        assertEquals(
            ErrorResolution.MAX_RETRIES_REACHED,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackCoordinator.MAX_NETWORK_RETRIES)
        )
        assertEquals(
            ErrorResolution.DISMISS_UNPLAYABLE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0, httpStatus = 404)
        )
    }

    @Test
    fun classifyError_audioDeviceErrorsRetryAndNeverDismiss() {
        // Audio focus / route changes (calls, nav prompts, Bluetooth hops) surface as AudioTrack errors
        assertEquals(
            ErrorResolution.RETRY_AUDIO_DEVICE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, 0)
        )
        assertEquals(
            ErrorResolution.RETRY_AUDIO_DEVICE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, 3)
        )
        assertEquals(
            ErrorResolution.RETRY_AUDIO_DEVICE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, 0)
        )
        assertEquals(
            ErrorResolution.MAX_RETRIES_REACHED,
            coordinator.classifyError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackCoordinator.MAX_AUDIO_DEVICE_RETRIES)
        )
    }

    @Test
    fun classifyError_transientHttpAndUnknownErrorsRetry() {
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0, httpStatus = 503)
        )
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0, httpStatus = 429)
        )
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_UNSPECIFIED, 0)
        )
        assertEquals(
            ErrorResolution.RETRY_NETWORK,
            coordinator.classifyError(PlaybackException.ERROR_CODE_TIMEOUT, 0)
        )
    }

    @Test
    fun classifyError_permanentErrorsDismiss() {
        assertEquals(
            ErrorResolution.DISMISS_UNPLAYABLE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0, httpStatus = 410)
        )
        assertEquals(
            ErrorResolution.DISMISS_UNPLAYABLE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, 0)
        )
        assertEquals(
            ErrorResolution.DISMISS_UNPLAYABLE,
            coordinator.classifyError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, 0)
        )
    }

    @Test
    fun retryDelay_backsOffAndCaps() {
        assertEquals(2_000L, coordinator.retryDelayMs(ErrorResolution.RETRY_NETWORK, 1))
        assertEquals(16_000L, coordinator.retryDelayMs(ErrorResolution.RETRY_NETWORK, 4))
        assertEquals(30_000L, coordinator.retryDelayMs(ErrorResolution.RETRY_NETWORK, 5))
        assertEquals(30_000L, coordinator.retryDelayMs(ErrorResolution.RETRY_NETWORK, 19))
        assertEquals(1_000L, coordinator.retryDelayMs(ErrorResolution.RETRY_AUDIO_DEVICE, 1))
        assertEquals(5_000L, coordinator.retryDelayMs(ErrorResolution.RETRY_AUDIO_DEVICE, 9))
    }
}
