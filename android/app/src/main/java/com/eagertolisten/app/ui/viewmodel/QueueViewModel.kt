package com.eagertolisten.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.data.repository.QueueSortRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class QueueFilterMode {
    QUEUE,
    HOT_LIST,
    UNPLAYED,
    PLAYED
}

class QueueViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PodcastRepository(application)

    private val _filterMode = MutableStateFlow(QueueFilterMode.QUEUE)
    val filterMode: StateFlow<QueueFilterMode> = _filterMode.asStateFlow()

    private val _activeSortRule = MutableStateFlow(repository.getSavedSortRule())
    val activeSortRule: StateFlow<QueueSortRule> = _activeSortRule.asStateFlow()

    val podcasts = repository.allPodcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queueEpisodes: StateFlow<List<Episode>> = repository.queueEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hotListEpisodes: StateFlow<List<Episode>> = repository.hotListEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unplayedEpisodes: StateFlow<List<Episode>> = repository.unplayedEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playedEpisodes: StateFlow<List<Episode>> = repository.playedEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayedEpisodes: StateFlow<List<Episode>> = combine(
        _filterMode,
        queueEpisodes,
        hotListEpisodes,
        unplayedEpisodes,
        playedEpisodes
    ) { filter, queue, hotList, unplayed, played ->
        when (filter) {
            QueueFilterMode.QUEUE -> queue
            QueueFilterMode.HOT_LIST -> hotList
            QueueFilterMode.UNPLAYED -> unplayed
            QueueFilterMode.PLAYED -> played
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilterMode(mode: QueueFilterMode) {
        _filterMode.value = mode
    }

    fun setSortRule(rule: QueueSortRule) {
        _activeSortRule.value = rule
        viewModelScope.launch {
            repository.sortQueue(rule)
        }
    }

    fun rebuildProgrammedQueue() {
        _activeSortRule.value = QueueSortRule.PROGRAMMED_FAIR
        repository.setSavedSortRule(QueueSortRule.PROGRAMMED_FAIR)
        viewModelScope.launch {
            repository.generateProgrammedQueue()
        }
    }

    fun removeEpisode(episodeId: String) {
        viewModelScope.launch {
            repository.removeEpisodeFromQueue(episodeId)
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            repository.clearQueue()
        }
    }

    // Play Next (Single episode priority insertion at top of playlist)
    fun enqueueEpisodeTop(episodeId: String, podcastId: String) {
        viewModelScope.launch {
            repository.enqueueEpisodeTop(episodeId, podcastId)
        }
    }

    fun enqueueEpisode(episodeId: String, podcastId: String) {
        viewModelScope.launch {
            repository.enqueueEpisode(episodeId, podcastId)
        }
    }

    fun setEpisodePlayed(episode: Episode, played: Boolean) {
        viewModelScope.launch {
            repository.setEpisodePlayed(episode.id, played)
            toast(if (played) "Marked as played" else "Marked as unplayed")
        }
    }

    fun markFeedEpisodesPlayed(episode: Episode, includeOlder: Boolean) {
        viewModelScope.launch {
            val count = repository.markFeedEpisodesPlayed(episode.id, includeOlder)
            toast(
                when (count) {
                    0 -> "Nothing to mark: those episodes are already played"
                    1 -> "Marked 1 episode as played"
                    else -> "Marked $count episodes of ${episode.podcastTitle} as played"
                }
            )
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(getApplication(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun toggleEpisodeDownload(episode: Episode) {
        viewModelScope.launch {
            val downloadMgr = com.eagertolisten.app.data.download.PodcastDownloadManager(getApplication())
            if (episode.isDownloaded) {
                downloadMgr.deleteDownloadedEpisode(episode.id)
            } else {
                downloadMgr.downloadEpisode(episode)
            }
        }
    }
}
