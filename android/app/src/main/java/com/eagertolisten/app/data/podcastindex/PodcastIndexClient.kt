package com.eagertolisten.app.data.podcastindex

import android.content.Context
import android.util.Log
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.ui.viewmodel.SearchResult
import com.eagertolisten.app.data.model.FeedUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class PodcastCategory(
    val id: String,
    val name: String,
    val iconName: String = "category"
)

class PodcastIndexClient(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "PodcastIndexClient"
        const val PREF_KEY_PI_API_KEY = "podcastindex_api_key"
        const val PREF_KEY_PI_API_SECRET = "podcastindex_api_secret"
        const val PREF_KEY_APPLE_SEARCH = "apple_search_enabled"

        val POPULAR_CATEGORIES = listOf(
            PodcastCategory("News", "News & Current Events"),
            PodcastCategory("Health & Fitness", "Health & Wellness"),
            PodcastCategory("Self-Help", "Self-Help & Mindset"),
            PodcastCategory("Technology", "Technology & Podcasting 2.0"),
            PodcastCategory("Science", "Science & Longevity"),
            PodcastCategory("Society & Culture", "Society & Culture"),
            PodcastCategory("Business", "Business & Finance"),
            PodcastCategory("History", "History"),
            PodcastCategory("True Crime", "True Crime"),
            PodcastCategory("Comedy", "Comedy"),
            PodcastCategory("Education", "Education")
        )

        val PODCAST_EAGER_TO_BE_HEALTHY = SearchResult(
            title = "Eager to Be Healthy - a Health and Happiness Podcast",
            author = "Michael Scott Eger",
            feedUrl = "https://api.riverside.com/hosting/drlxDc7x.rss",
            artworkUrl = "https://hosting-media.riverside.com/media/podcasts/25c060e8-e598-4782-920d-b96b89a1ffb0/logos/6046bb02-7f6e-4dfa-9ece-f617d9ce3508.jpeg",
            genres = listOf("Health & Fitness", "Self-Help", "Wellness", "Featured")
        )

        val PODCAST_NO_AGENDA = SearchResult(
            title = "No Agenda Show",
            author = "Adam Curry & John C. Dvorak",
            feedUrl = "https://feeds.noagendaassets.com/noagenda.xml",
            artworkUrl = "https://noagendaassets.com/enc/1792709117.49_na-1914-art-feed.jpg",
            genres = listOf("News", "Current Events", "Podcasting 2.0", "Featured")
        )

        val PROMOTED_PODCASTS = listOf(
            PODCAST_EAGER_TO_BE_HEALTHY,
            PODCAST_NO_AGENDA,
            SearchResult(
                title = "Podcasting 2.0",
                author = "Adam Curry & Dave Jones",
                feedUrl = "https://feeds.podcastindex.org/pc20.xml",
                artworkUrl = "https://noagendaassets.com/enc/1684513486.722_pcifeedimage.png",
                genres = listOf("Technology", "PodcastIndex.org", "Featured")
            ),
            SearchResult(
                title = "Huberman Lab",
                author = "Dr. Andrew Huberman",
                feedUrl = "https://feeds.megaphone.fm/hubermanlab",
                artworkUrl = "https://megaphone.imgix.net/podcasts/042e6144-725e-11ec-a75d-c38f702aecad/image/ee4f0b7b466ca35620792970d9bce2d2.jpg?ixlib=rails-4.3.1&max-w=3000&max-h=3000&fit=crop&auto=format,compress",
                genres = listOf("Science", "Health & Fitness", "Featured")
            ),
            SearchResult(
                title = "The Peter Attia Drive",
                author = "Peter Attia, MD",
                feedUrl = "https://peterattiadrive.libsyn.com/rss",
                artworkUrl = "https://static.libsyn.com/p/assets/e/d/6/0/ed60b3def918dca516c3140a3186d450/the_Drive_public.png",
                genres = listOf("Health & Fitness", "Medicine", "Featured")
            )
        )
    }

    private fun getCredentials(): Pair<String?, String?> {
        val prefs = context.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)
        val key = prefs.getString(PREF_KEY_PI_API_KEY, null)?.trim()
        val secret = prefs.getString(PREF_KEY_PI_API_SECRET, null)?.trim()
        return Pair(key?.ifBlank { null }, secret?.ifBlank { null })
    }

    private fun generateSha1AuthHeader(apiKey: String, apiSecret: String, timestamp: String): String {
        val raw = apiKey + apiSecret + timestamp
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * "Also search Apple Podcasts" in Settings (on unless turned off). Apple's directory is a closed
     * service; with this off the app never contacts Apple and searches Podcast Index only.
     */
    fun appleSearchEnabled(): Boolean =
        context.getSharedPreferences("eager_settings", Context.MODE_PRIVATE).getBoolean(PREF_KEY_APPLE_SEARCH, true)

    /** False when Apple search is off and there's no Podcast Index key: search has nowhere to look. */
    fun hasSearchSource(): Boolean {
        val (key, secret) = getCredentials()
        return appleSearchEnabled() || (key != null && secret != null)
    }

    /** One directory failing (offline, down, bad key) must never sink the whole search. */
    private inline fun safely(source: String, block: () -> List<SearchResult>): List<SearchResult> = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$source search failed: ${e.message}")
        emptyList()
    }

    /** The house shows pinned on top: same rules as before for searches and for categories. */
    private fun housePins(text: String, category: Boolean): List<SearchResult> {
        val q = text.lowercase().trim()
        val pinned = mutableListOf<SearchResult>()
        if (q.contains("no agenda") || (category && (q.contains("news") || q.contains("current")))) {
            pinned.add(PODCAST_NO_AGENDA)
        }
        val healthy = if (category) {
            q.contains("health") || q.contains("fitness") || q.contains("wellness") ||
                q.contains("self-help") || q.contains("self help") || q.contains("mindset")
        } else {
            q.contains("eager") || q == "health" || q.contains("wellness") || q.contains("self help") || q.contains("self-help")
        }
        if (healthy) pinned.add(PODCAST_EAGER_TO_BE_HEALTHY)
        return pinned
    }

    /**
     * Search Podcast Index (when you've added a key) and Apple Podcasts at the same time and merge
     * the answers (ShowSearch). Each directory misses shows the other has: Podcast Index can lag on
     * brand-new shows, Apple leaves out Podcasting 2.0-only ones. House shows are pinned on top for
     * matching searches.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret) = getCredentials()
        val merged = coroutineScope {
            val pi = async {
                if (apiKey != null && apiSecret != null) safely("Podcast Index") { searchPodcastIndex(query, apiKey, apiSecret) }
                else emptyList()
            }
            val apple = async { if (appleSearchEnabled()) safely("Apple Podcasts") { searchItunes(query) } else emptyList() }
            ShowSearch.merge(query, listOf(pi.await(), apple.await()))
        }
        (housePins(query, category = false) + merged).distinctBy { FeedUrl.matchKey(it.feedUrl) }
    }

    /** Browse a category: Podcast Index's category list and Apple's search for it, merged. */
    suspend fun discoverByCategory(category: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val catLower = category.lowercase().trim()
        val appleTerm = when {
            catLower.contains("self-help") || catLower.contains("self help") -> "self help"
            catLower.contains("health") || catLower.contains("wellness") -> "health wellness"
            catLower.contains("news") -> "news"
            else -> category
        }
        val (apiKey, apiSecret) = getCredentials()
        val merged = coroutineScope {
            val pi = async {
                if (apiKey != null && apiSecret != null) safely("Podcast Index") { discoverPodcastIndexCategory(category, apiKey, apiSecret) }
                else emptyList()
            }
            val apple = async { if (appleSearchEnabled()) safely("Apple Podcasts") { searchItunes(appleTerm) } else emptyList() }
            // No typed words to match against: keep each directory's own order
            ShowSearch.merge("", listOf(pi.await(), apple.await()))
        }
        (housePins(category, category = true) + merged).distinctBy { FeedUrl.matchKey(it.feedUrl) }
    }

    /** The feed address behind an Apple Podcasts (or Overcast) show link. */
    suspend fun lookupAppleFeed(appleId: String): SearchResult? = withContext(Dispatchers.IO) {
        if (!appleSearchEnabled()) {
            throw IOException("Apple Podcasts search is off in Settings. Turn it on to open Apple Podcasts links, or paste the show's feed link.")
        }
        val request = Request.Builder()
            .url("https://itunes.apple.com/lookup?id=${URLEncoder.encode(appleId, "UTF-8")}&entity=podcast")
            .header("User-Agent", PodcastRepository.USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Apple Podcasts lookup: HTTP ${response.code}")
            ShowSearch.fromApple(response.body?.string().orEmpty()).firstOrNull()
        }
    }

    private fun podcastIndexGet(path: String, apiKey: String, apiSecret: String): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val request = Request.Builder()
            .url("https://api.podcastindex.org/api/1.0/$path")
            .header("User-Agent", "EagerToListen/1.0")
            .header("X-Auth-Date", timestamp)
            .header("X-Auth-Key", apiKey)
            .header("Authorization", generateSha1AuthHeader(apiKey, apiSecret, timestamp))
            .build()
        httpClient.newCall(request).execute().use { response ->
            // A wrong key comes back as 401: say so in the log instead of silently showing nothing
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}")
            return response.body?.string().orEmpty()
        }
    }

    private fun searchPodcastIndex(query: String, apiKey: String, apiSecret: String): List<SearchResult> =
        ShowSearch.fromPodcastIndex(
            podcastIndexGet("search/byterm?q=${URLEncoder.encode(query, "UTF-8")}&max=40", apiKey, apiSecret)
        )

    private fun discoverPodcastIndexCategory(category: String, apiKey: String, apiSecret: String): List<SearchResult> =
        ShowSearch.fromPodcastIndex(
            podcastIndexGet("podcasts/bycategory?cat=${URLEncoder.encode(category, "UTF-8")}&max=40", apiKey, apiSecret),
            genre = category
        )

    private fun searchItunes(term: String): List<SearchResult> {
        val encoded = URLEncoder.encode(term.trim(), "UTF-8")
        val request = Request.Builder()
            .url("https://itunes.apple.com/search?term=$encoded&media=podcast&entity=podcast&limit=40")
            .header("User-Agent", PodcastRepository.USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}")
            return ShowSearch.fromApple(response.body?.string().orEmpty())
        }
    }
}
