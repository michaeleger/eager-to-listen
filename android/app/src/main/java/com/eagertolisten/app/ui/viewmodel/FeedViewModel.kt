package com.eagertolisten.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.repository.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String,
    val genres: List<String>,
    // Used to merge the same show found in more than one directory (ShowSearch)
    val podcastGuid: String? = null,
    val appleId: String? = null,
    val newestEpisodeAt: Long = 0L,
    val dead: Boolean = false,
    val source: String = ""
)

class FeedViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PodcastRepository(application)
    private val httpClient = PodcastRepository.getOkHttpClient()

    val subscribedPodcasts: StateFlow<List<Podcast>> = repository.allPodcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveEpisodes: StateFlow<List<Episode>> = repository.liveEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _selectedPodcast = MutableStateFlow<Podcast?>(null)
    val selectedPodcast: StateFlow<Podcast?> = _selectedPodcast.asStateFlow()

    private val _feedEpisodes = MutableStateFlow<List<Episode>>(emptyList())
    val feedEpisodes: StateFlow<List<Episode>> = _feedEpisodes.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private var podcastJob: Job? = null
    private var episodesJob: Job? = null

    fun selectPodcast(podcast: Podcast?) {
        podcastJob?.cancel()
        episodesJob?.cancel()
        _selectedPodcast.value = podcast
        if (podcast != null) {
            podcastJob = viewModelScope.launch {
                repository.getPodcast(podcast.id).collect {
                    if (it != null) {
                        _selectedPodcast.value = it
                    }
                }
            }
            episodesJob = viewModelScope.launch {
                repository.getEpisodesForPodcast(podcast.id).collect {
                    _feedEpisodes.value = it
                }
            }
        } else {
            _feedEpisodes.value = emptyList()
        }
    }

    private val podcastIndexClient = com.eagertolisten.app.data.podcastindex.PodcastIndexClient(application)
    val promotedPodcasts = com.eagertolisten.app.data.podcastindex.PodcastIndexClient.PROMOTED_PODCASTS
    val categories = com.eagertolisten.app.data.podcastindex.PodcastIndexClient.POPULAR_CATEGORIES

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    fun searchPodcasts(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        // A pasted link (feed, Apple Podcasts, a show's web page) opens Add show instead of searching
        if (com.eagertolisten.app.data.model.FeedLink.resolve(query) !is com.eagertolisten.app.data.model.FeedLink.Target.Search) {
            openAddShow(query)
            return
        }
        _selectedCategory.value = null
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = podcastIndexClient.search(query.trim())
                _searchResults.value = results
                if (results.isEmpty()) _userMessage.value = "No shows found. Check your connection, or paste the show's feed or web page link."
            } catch (e: Exception) {
                _userMessage.value = "Search failed: ${e.localizedMessage}"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun discoverCategory(category: String) {
        _selectedCategory.value = category
        viewModelScope.launch {
            _isSearching.value = true
            try {
                _searchResults.value = podcastIndexClient.discoverByCategory(category)
            } catch (e: Exception) {
                _userMessage.value = "Couldn't load that category: ${e.localizedMessage}"
            } finally {
                _isSearching.value = false
            }
        }
    }

    // ---------- Add show: links opened, shared or pasted (default priority 2) ----------

    sealed class AddShowState {
        object Loading : AddShowState()
        data class Ready(val preview: com.eagertolisten.app.data.model.FeedPreview) : AddShowState()
        data class Subscribing(val preview: com.eagertolisten.app.data.model.FeedPreview) : AddShowState()
        data class Failed(val message: String) : AddShowState()
    }

    private val _addShowLink = MutableStateFlow<String?>(null)
    /** The link being added; null when the Add show sheet is closed. */
    val addShowLink: StateFlow<String?> = _addShowLink.asStateFlow()

    private val _addShowState = MutableStateFlow<AddShowState>(AddShowState.Loading)
    val addShowState: StateFlow<AddShowState> = _addShowState.asStateFlow()

    private var addShowJob: Job? = null

    fun openAddShow(link: String) {
        addShowJob?.cancel()
        _addShowLink.value = link
        _addShowState.value = AddShowState.Loading
        addShowJob = viewModelScope.launch {
            _addShowState.value = repository.previewFeedLink(link).fold(
                onSuccess = { AddShowState.Ready(it) },
                onFailure = { AddShowState.Failed(it.message ?: "Couldn't read that feed") }
            )
        }
    }

    fun closeAddShow() {
        addShowJob?.cancel()
        _addShowLink.value = null
    }

    fun subscribeFromPreview(preview: com.eagertolisten.app.data.model.FeedPreview, priority: Int) {
        addShowJob?.cancel()
        _addShowState.value = AddShowState.Subscribing(preview)
        addShowJob = viewModelScope.launch {
            repository.subscribe(preview.feedUrl, priority).onSuccess {
                _addShowLink.value = null
                _userMessage.value = if (preview.existing != null) {
                    "Following ${it.title} again (your history and settings are kept)"
                } else {
                    "Subscribed to ${it.title} at priority $priority"
                }
            }.onFailure {
                _addShowState.value = AddShowState.Failed("Couldn't subscribe: ${it.localizedMessage}")
            }
        }
    }

    fun subscribe(feedUrl: String, priority: Int = 3) {
        val currentSubscribed = subscribedPodcasts.value.any { com.eagertolisten.app.data.model.FeedUrl.sameFeed(it.feedUrl, feedUrl) }
        if (currentSubscribed) {
            _userMessage.value = "Already subscribed to this podcast"
            return
        }

        val alreadyFollowingIds = subscribedPodcasts.value.map { it.id }.toSet()
        viewModelScope.launch {
            _userMessage.value = "Subscribing to feed..."
            val result = repository.subscribe(feedUrl.trim(), priority)
            result.onSuccess {
                // The repository matched a show you already follow (same podcast:guid, different address)
                _userMessage.value = if (it.id in alreadyFollowingIds) {
                    "You already follow ${it.title} (same show, different address)"
                } else {
                    "Subscribed to ${it.title}!"
                }
            }.onFailure {
                _userMessage.value = "Failed to subscribe: ${it.localizedMessage}"
            }
        }
    }

    fun updateFeedRules(
        podcastId: String,
        priority: Int,
        skipIntro: Int,
        skipOutro: Int,
        speed: Float,
        autoAdd: Boolean,
        maxEpisodes: Int,
        category: String,
        customDonationUrl: String? = null,
        playOrderStrategy: String,
        newsRetentionDays: Int,
        consecutivePlayLimit: Int,
        notifyUser: Boolean = false
    ) {
        viewModelScope.launch {
            repository.updateFeedRules(
                podcastId = podcastId,
                priority = priority,
                skipIntro = skipIntro,
                skipOutro = skipOutro,
                speed = speed,
                autoAdd = autoAdd,
                maxEpisodes = maxEpisodes,
                category = category,
                customDonationUrl = customDonationUrl,
                playOrderStrategy = playOrderStrategy,
                newsRetentionDays = newsRetentionDays,
                consecutivePlayLimit = consecutivePlayLimit
            )
            if (notifyUser) {
                _userMessage.value = "Feed settings updated!"
            }
        }
    }

    fun updateCustomDonationUrl(podcastId: String, url: String?) {
        viewModelScope.launch {
            repository.updateCustomDonationUrl(podcastId, url)
        }
    }

    fun unsubscribe(podcastId: String) {
        viewModelScope.launch {
            repository.unsubscribe(podcastId)
            _selectedPodcast.value = null
            _userMessage.value = "Podcast removed"
        }
    }

    private val _isRefreshingAll = MutableStateFlow(false)
    val isRefreshingAll: StateFlow<Boolean> = _isRefreshingAll.asStateFlow()

    fun refreshAllFeeds(silent: Boolean = false) {
        viewModelScope.launch {
            _isRefreshingAll.value = true
            if (!silent) {
                _userMessage.value = "Refreshing all feeds..."
            }
            val (successCount, errors) = repository.refreshAllFeeds()
            _isRefreshingAll.value = false
            if (!silent) {
                if (errors.isEmpty()) {
                    _userMessage.value = "All $successCount feeds updated successfully!"
                } else {
                    _userMessage.value = "Updated $successCount feeds (${errors.size} failed: ${errors.first()})"
                }
            }
        }
    }

    fun refreshFeed(podcastId: String) {
        viewModelScope.launch {
            _userMessage.value = "Refreshing episodes..."
            val res = repository.refreshFeed(podcastId)
            res.onSuccess {
                _userMessage.value = "Feed updated"
            }.onFailure {
                _userMessage.value = "Refresh failed: ${it.message ?: "Unknown error"}"
            }
        }
    }

    fun enqueueEpisodeTop(episodeId: String, podcastId: String) {
        viewModelScope.launch {
            repository.enqueueEpisodeTop(episodeId, podcastId)
            _userMessage.value = "Episode added as Play Next"
        }
    }

    fun enqueueEpisode(episodeId: String, podcastId: String) {
        viewModelScope.launch {
            repository.enqueueEpisode(episodeId, podcastId)
            _userMessage.value = "Episode added to playlist"
        }
    }

    fun setEpisodePlayed(episode: Episode, played: Boolean) {
        viewModelScope.launch {
            repository.setEpisodePlayed(episode.id, played)
            _userMessage.value = if (played) "Marked as played" else "Marked as unplayed"
        }
    }

    fun markFeedEpisodesPlayed(episode: Episode, includeOlder: Boolean) {
        viewModelScope.launch {
            val count = repository.markFeedEpisodesPlayed(episode.id, includeOlder)
            _userMessage.value = when (count) {
                0 -> "Nothing to mark: those episodes are already played"
                1 -> "Marked 1 episode as played"
                else -> "Marked $count episodes as played"
            }
        }
    }

    fun toggleEpisodeDownload(episode: Episode) {
        viewModelScope.launch {
            val downloadMgr = com.eagertolisten.app.data.download.PodcastDownloadManager(getApplication())
            if (episode.isDownloaded) {
                _userMessage.value = "Removing downloaded episode..."
                downloadMgr.deleteDownloadedEpisode(episode.id)
                _userMessage.value = "Download removed"
            } else {
                _userMessage.value = "Downloading for offline listening..."
                val ok = downloadMgr.downloadEpisode(episode)
                if (ok) {
                    _userMessage.value = "Download complete"
                } else {
                    _userMessage.value = "Download failed"
                }
            }
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }
}
