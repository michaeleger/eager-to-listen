package com.eagertolisten.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eagertolisten.app.data.model.Chapter
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.TranscriptCue
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.service.PlaybackController
import com.eagertolisten.app.service.PlaybackState
import com.eagertolisten.app.service.PlaybackStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PodcastRepository(application)

    val playbackState: StateFlow<PlaybackState> = PlaybackStateManager.state

    private val _currentPodcast = MutableStateFlow<Podcast?>(null)
    val currentPodcast: StateFlow<Podcast?> = _currentPodcast.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _transcriptCues = MutableStateFlow<List<TranscriptCue>>(emptyList())
    val transcriptCues: StateFlow<List<TranscriptCue>> = _transcriptCues.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private var currentEpisodeId: String? = null

    init {
        PlaybackController.initialize(application)

        // Observe episode change to fetch Podcasting 2.0 chapters, transcript & podcast details
        viewModelScope.launch {
            playbackState.collect { state ->
                val ep = state.currentEpisode
                if (ep != null) {
                    if (ep.id != currentEpisodeId) {
                        currentEpisodeId = ep.id
                        loadPodcasting20Data(ep)
                        _currentPodcast.value = repository.getPodcastById(ep.podcastId)
                    }
                } else {
                    currentEpisodeId = null
                    _currentPodcast.value = null
                }
            }
        }
    }

    private fun loadPodcasting20Data(episode: Episode) {
        viewModelScope.launch {
            _chapters.value = repository.fetchChapters(episode)

            if (!episode.transcriptUrl.isNullOrBlank()) {
                _transcriptCues.value = repository.fetchTranscript(episode)
            } else {
                _transcriptCues.value = emptyList()
            }
        }
    }

    fun playEpisode(episode: Episode, podcastId: String?) {
        PlaybackController.playEpisode(getApplication(), episode, podcastId)
    }

    fun togglePlayPause() {
        PlaybackController.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        PlaybackController.seekTo(positionMs)
    }

    fun seekBack() {
        // 15-second rewind
        PlaybackController.seekBack()
    }

    fun seekForward() {
        // 90-second forward skip
        PlaybackController.seekForward()
    }

    fun setSpeed(speed: Float) {
        val currentEp = PlaybackStateManager.state.value.currentEpisode
        if (currentEp != null && !currentEp.isLive) {
            viewModelScope.launch {
                repository.updatePodcastSpeed(currentEp.podcastId, speed)
            }
        }
        PlaybackController.setSpeed(speed)
    }

    fun setExpanded(expanded: Boolean) {
        _isExpanded.value = expanded
    }
}
