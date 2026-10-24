package com.eagertolisten.app.data.podcastindex

import android.content.Context
import android.util.Log
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.ui.viewmodel.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
     * Search podcasts using PodcastIndex.org API (if keys present) or fallback to iTunes API.
     * Pins promoted matches (No Agenda for news, Eager to be Healthy for health/self-help) to the top.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val qLower = query.lowercase().trim()
        val pinned = mutableListOf<SearchResult>()
        if (qLower.contains("no agenda")) {
            pinned.add(PODCAST_NO_AGENDA)
        }
        if (qLower.contains("eager") || qLower == "health" || qLower.contains("wellness") ||
            qLower.contains("self help") || qLower.contains("self-help")) {
            pinned.add(PODCAST_EAGER_TO_BE_HEALTHY)
        }

        val dynamicResults = mutableListOf<SearchResult>()
        val (apiKey, apiSecret) = getCredentials()
        if (apiKey != null && apiSecret != null) {
            try {
                val results = searchPodcastIndex(query, apiKey, apiSecret)
                if (results.isNotEmpty()) {
                    dynamicResults.addAll(results)
                }
            } catch (e: Exception) {
                Log.w(TAG, "PodcastIndex.org API query failed, falling back to iTunes: ${e.message}")
            }
        }
        if (dynamicResults.isEmpty()) {
            dynamicResults.addAll(searchItunes(query))
        }

        (pinned + dynamicResults).distinctBy { it.feedUrl.lowercase() }
    }

    /**
     * Discover podcasts by category.
     * Hardcodes No Agenda for News & Current Events, and Eager to be Healthy for Health & Wellness and Self-Help.
     */
    suspend fun discoverByCategory(category: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val catLower = category.lowercase().trim()
        val pinned = mutableListOf<SearchResult>()

        if (catLower.contains("news") || catLower.contains("current")) {
            pinned.add(PODCAST_NO_AGENDA)
        }
        if (catLower.contains("health") || catLower.contains("fitness") || catLower.contains("wellness") ||
            catLower.contains("self-help") || catLower.contains("self help") || catLower.contains("mindset")) {
            pinned.add(PODCAST_EAGER_TO_BE_HEALTHY)
        }

        val dynamicResults = mutableListOf<SearchResult>()
        val (apiKey, apiSecret) = getCredentials()
        if (apiKey != null && apiSecret != null) {
            try {
                val results = discoverPodcastIndexCategory(category, apiKey, apiSecret)
                if (results.isNotEmpty()) {
                    dynamicResults.addAll(results)
                }
            } catch (e: Exception) {
                Log.w(TAG, "PodcastIndex category query failed, falling back: ${e.message}")
            }
        }
        if (dynamicResults.isEmpty()) {
            // For category query on iTunes, search with category term
            val term = when {
                catLower.contains("self-help") || catLower.contains("self help") -> "self help"
                catLower.contains("health") || catLower.contains("wellness") -> "health wellness"
                catLower.contains("news") -> "news"
                else -> category
            }
            dynamicResults.addAll(searchItunes(term))
        }

        (pinned + dynamicResults).distinctBy { it.feedUrl.lowercase() }
    }

    private fun searchPodcastIndex(query: String, apiKey: String, apiSecret: String): List<SearchResult> {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val authHeader = generateSha1AuthHeader(apiKey, apiSecret, timestamp)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.podcastindex.org/api/1.0/search/byterm?q=$encoded&max=30"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "EagerToListen/1.0")
            .header("X-Auth-Date", timestamp)
            .header("X-Auth-Key", apiKey)
            .header("Authorization", authHeader)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val feeds = root.optJSONArray("feeds") ?: return emptyList()
            val list = mutableListOf<SearchResult>()
            for (i in 0 until feeds.length()) {
                val obj = feeds.getJSONObject(i)
                val feedUrl = obj.optString("url", "")
                if (feedUrl.isNotBlank()) {
                    list.add(
                        SearchResult(
                            title = obj.optString("title", "Untitled"),
                            author = obj.optString("author", ""),
                            feedUrl = feedUrl,
                            artworkUrl = obj.optString("artwork", obj.optString("image", "")),
                            genres = listOf(obj.optString("contentType", "PodcastIndex"))
                        )
                    )
                }
            }
            return list
        }
    }

    private fun discoverPodcastIndexCategory(category: String, apiKey: String, apiSecret: String): List<SearchResult> {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val authHeader = generateSha1AuthHeader(apiKey, apiSecret, timestamp)
        val encoded = URLEncoder.encode(category, "UTF-8")
        val url = "https://api.podcastindex.org/api/1.0/podcasts/bycategory?cat=$encoded&max=30"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "EagerToListen/1.0")
            .header("X-Auth-Date", timestamp)
            .header("X-Auth-Key", apiKey)
            .header("Authorization", authHeader)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val feeds = root.optJSONArray("feeds") ?: return emptyList()
            val list = mutableListOf<SearchResult>()
            for (i in 0 until feeds.length()) {
                val obj = feeds.getJSONObject(i)
                val feedUrl = obj.optString("url", "")
                if (feedUrl.isNotBlank()) {
                    list.add(
                        SearchResult(
                            title = obj.optString("title", "Untitled"),
                            author = obj.optString("author", ""),
                            feedUrl = feedUrl,
                            artworkUrl = obj.optString("artwork", obj.optString("image", "")),
                            genres = listOf(category)
                        )
                    )
                }
            }
            return list
        }
    }

    private fun searchItunes(term: String): List<SearchResult> {
        val encoded = URLEncoder.encode(term.trim(), "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=podcast&limit=25"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PodcastRepository.USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val array = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<SearchResult>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val feedUrl = obj.optString("feedUrl", "")
                if (feedUrl.isNotBlank()) {
                    val genre = obj.optString("primaryGenreName", "Podcast")
                    list.add(
                        SearchResult(
                            title = obj.optString("collectionName", "Untitled"),
                            author = obj.optString("artistName", ""),
                            feedUrl = feedUrl,
                            artworkUrl = obj.optString("artworkUrl600", obj.optString("artworkUrl100", "")),
                            genres = listOf(genre)
                        )
                    )
                }
            }
            return list
        }
    }
}
