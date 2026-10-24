package com.eagertolisten.app.parser

import android.util.Xml
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Person
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.PodcastValue
import com.eagertolisten.app.data.model.Soundbite
import com.eagertolisten.app.data.model.ValueRecipient
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedFeed(
    val podcast: Podcast,
    val episodes: List<Episode>
)

class FeedParser {

    private val rfc822FormatsThreadLocal = object : ThreadLocal<Array<SimpleDateFormat>>() {
        override fun initialValue(): Array<SimpleDateFormat> = arrayOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US),
            SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm Z", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        )
    }

    private val isoFormatsThreadLocal = object : ThreadLocal<Array<SimpleDateFormat>>() {
        override fun initialValue(): Array<SimpleDateFormat> = arrayOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
    }

    fun parse(inputStream: InputStream, feedUrl: String, podcastId: String = feedUrl): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(inputStream, null)

        var channelTitle = ""
        var channelDescription = ""
        var channelAuthor = ""
        var channelArtworkUrl = ""
        var channelFundingUrl: String? = null
        var channelFundingMessage: String? = null
        var channelValueJson: String? = null
        var channelCategory = ""
        var channelItunesType = ""

        val episodes = mutableListOf<Episode>()
        var insideChannel = false
        var insideItem = false

        // Temp episode vars
        var itemTitle = ""
        var itemGuid = ""
        var itemAudioUrl = ""
        var itemDescription = ""
        var itemPubDate: Long = 0
        var itemDurationSec: Long = 0
        var itemArtworkUrl: String? = null
        var itemTranscriptUrl: String? = null
        var itemTranscriptType: String? = null
        var itemChaptersUrl: String? = null
        var itemIsLive = false
        var itemLiveStatus: String? = null
        var itemFundingUrl: String? = null
        var itemValueJson: String? = null
        val itemPersons = mutableListOf<Person>()
        val itemSoundbites = mutableListOf<Soundbite>()

        // Temp value vars
        var insideValue = false
        var currentValueType = "lightning"
        var currentValueMethod = "keysend"
        var currentValueSuggested: Double? = null
        val currentRecipients = mutableListOf<ValueRecipient>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.lowercase() ?: ""
            val prefix = parser.prefix?.lowercase() ?: ""

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        name == "channel" -> insideChannel = true
                        name == "item" || name == "liveitem" -> {
                            insideItem = true
                            val isLiveTag = (name == "liveitem")
                            val rawStatus = if (isLiveTag) {
                                parser.getAttributeValue(null, "status")?.lowercase()?.trim() ?: "live"
                            } else null
                            val startTimeStr = if (isLiveTag) parser.getAttributeValue(null, "start") else null
                            val endTimeStr = if (isLiveTag) parser.getAttributeValue(null, "end") else null

                            // Canonical Podcasting 2.0 Live Beacon Validation:
                            // 1. status MUST be explicitly "live". If "ended" or "pending", it is not live.
                            // 2. If end time is provided and in the past, broadcast has concluded.
                            // 3. If start time is provided and in the future, broadcast has not started yet.
                            val isLegitLive = if (isLiveTag && rawStatus == "live") {
                                val now = System.currentTimeMillis()
                                val startMs = startTimeStr?.let { parseIsoDate(it) } ?: 0L
                                val endMs = endTimeStr?.let { parseIsoDate(it) } ?: 0L
                                val notYetStarted = startMs > 0L && now < startMs
                                val alreadyEnded = endMs > 0L && now > endMs
                                !notYetStarted && !alreadyEnded
                            } else {
                                false
                            }

                            itemIsLive = isLegitLive
                            itemLiveStatus = if (isLiveTag) {
                                if (isLegitLive) "live" else (rawStatus ?: "ended")
                            } else null
                            itemTitle = ""
                            itemGuid = ""
                            itemAudioUrl = ""
                            itemDescription = ""
                            itemPubDate = System.currentTimeMillis()
                            itemDurationSec = 0
                            itemArtworkUrl = null
                            itemTranscriptUrl = null
                            itemTranscriptType = null
                            itemChaptersUrl = null
                            itemFundingUrl = null
                            itemValueJson = null
                            itemPersons.clear()
                            itemSoundbites.clear()
                        }
                        insideItem -> {
                            when {
                                name == "title" -> itemTitle = parser.nextText()
                                name == "guid" -> itemGuid = parser.nextText()
                                name == "enclosure" -> {
                                    val url = parser.getAttributeValue(null, "url")
                                    val type = parser.getAttributeValue(null, "type")
                                    if (!url.isNullOrBlank() && (type == null || type.contains("audio") || type.contains("mpeg") || type.contains("m3u8") || type.contains("aac") || type.contains("ogg"))) {
                                        itemAudioUrl = url
                                    }
                                }
                                name == "contentlink" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!href.isNullOrBlank() && itemAudioUrl.isBlank()) {
                                        itemAudioUrl = href
                                    }
                                }
                                name == "description" || name == "encoded" -> {
                                    val rawDesc = parser.nextText()
                                    if (rawDesc.isNotBlank() && (itemDescription.isBlank() || name == "encoded")) {
                                        itemDescription = if (rawDesc.length > MAX_EPISODE_DESCRIPTION_CHARS) {
                                            rawDesc.substring(0, MAX_EPISODE_DESCRIPTION_CHARS)
                                        } else {
                                            rawDesc
                                        }
                                    }
                                }
                                name == "pubdate" -> {
                                    val text = parser.nextText()
                                    itemPubDate = parseDate(text)
                                }
                                name == "duration" -> {
                                    val text = parser.nextText()
                                    itemDurationSec = parseDuration(text)
                                }
                                name == "image" && (prefix == "itunes" || prefix.isEmpty()) -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!href.isNullOrBlank()) {
                                        itemArtworkUrl = href
                                    }
                                }
                                // Podcasting 2.0: Transcript
                                name == "transcript" -> {
                                    itemTranscriptUrl = parser.getAttributeValue(null, "url")
                                    itemTranscriptType = parser.getAttributeValue(null, "type")
                                }
                                // Podcasting 2.0: Chapters
                                name == "chapters" -> {
                                    itemChaptersUrl = parser.getAttributeValue(null, "url")
                                }
                                // Podcasting 2.0: Person
                                name == "person" -> {
                                    val role = parser.getAttributeValue(null, "role")
                                    val group = parser.getAttributeValue(null, "group")
                                    val img = parser.getAttributeValue(null, "img")
                                    val href = parser.getAttributeValue(null, "href")
                                    val personName = parser.nextText()
                                    if (personName.isNotBlank()) {
                                        itemPersons.add(Person(personName.trim(), role, group, img, href))
                                    }
                                }
                                // Podcasting 2.0: Soundbite
                                name == "soundbite" -> {
                                    val start = parser.getAttributeValue(null, "startTime")?.toDoubleOrNull() ?: 0.0
                                    val duration = parser.getAttributeValue(null, "duration")?.toDoubleOrNull() ?: 30.0
                                    val soundbiteTitle = parser.nextText()
                                    itemSoundbites.add(Soundbite(start, duration, soundbiteTitle.ifBlank { null }))
                                }
                                // Podcasting 2.0: Funding
                                name == "funding" -> {
                                    itemFundingUrl = parser.getAttributeValue(null, "url")
                                }
                                // Podcasting 2.0: Value (Lightning / V4V)
                                name == "value" -> {
                                    insideValue = true
                                    currentValueType = parser.getAttributeValue(null, "type") ?: "lightning"
                                    currentValueMethod = parser.getAttributeValue(null, "method") ?: "keysend"
                                    currentValueSuggested = parser.getAttributeValue(null, "suggested")?.toDoubleOrNull()
                                    currentRecipients.clear()
                                }
                                name == "valuerecipient" -> {
                                    val rName = parser.getAttributeValue(null, "name") ?: "Recipient"
                                    val rType = parser.getAttributeValue(null, "type") ?: "node"
                                    val rAddress = parser.getAttributeValue(null, "address") ?: ""
                                    val rSplit = parser.getAttributeValue(null, "split")?.toIntOrNull() ?: 1
                                    val rCustomKey = parser.getAttributeValue(null, "customKey")
                                    val rCustomValue = parser.getAttributeValue(null, "customValue")
                                    val rFee = parser.getAttributeValue(null, "fee")?.toBooleanStrictOrNull() ?: false
                                    if (rAddress.isNotBlank()) {
                                        currentRecipients.add(ValueRecipient(rName, rType, rAddress, rSplit, rCustomKey, rCustomValue, rFee))
                                    }
                                }
                            }
                        }
                        insideChannel && !insideItem -> {
                            when {
                                name == "title" -> {
                                    val t = parser.nextText().trim()
                                    if (channelTitle.isBlank() && t.isNotBlank()) {
                                        channelTitle = t
                                    }
                                }
                                name == "description" -> {
                                    val rawDesc = parser.nextText()
                                    channelDescription = if (rawDesc.length > MAX_CHANNEL_DESCRIPTION_CHARS) {
                                        rawDesc.substring(0, MAX_CHANNEL_DESCRIPTION_CHARS)
                                    } else {
                                        rawDesc
                                    }
                                }
                                (name == "author" && prefix == "itunes") || (name == "managingeditor") -> {
                                    channelAuthor = parser.nextText()
                                }
                                name == "image" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!href.isNullOrBlank()) {
                                        channelArtworkUrl = href
                                    }
                                }
                                name == "url" && channelArtworkUrl.isBlank() -> {
                                    channelArtworkUrl = parser.nextText()
                                }
                                name == "category" -> {
                                    val catText = parser.getAttributeValue(null, "text") ?: ""
                                    channelCategory = if (channelCategory.isBlank()) catText else "$channelCategory, $catText"
                                }
                                name == "type" && prefix == "itunes" -> {
                                    channelItunesType = parser.nextText().trim().lowercase()
                                }
                                // Podcasting 2.0: Funding
                                name == "funding" -> {
                                    channelFundingUrl = parser.getAttributeValue(null, "url")
                                    channelFundingMessage = runCatching { parser.nextText() }.getOrNull()?.trim()
                                }
                                // Podcasting 2.0: Value (Lightning / V4V)
                                name == "value" -> {
                                    insideValue = true
                                    currentValueType = parser.getAttributeValue(null, "type") ?: "lightning"
                                    currentValueMethod = parser.getAttributeValue(null, "method") ?: "keysend"
                                    currentValueSuggested = parser.getAttributeValue(null, "suggested")?.toDoubleOrNull()
                                    currentRecipients.clear()
                                }
                                name == "valuerecipient" -> {
                                    val rName = parser.getAttributeValue(null, "name") ?: "Recipient"
                                    val rType = parser.getAttributeValue(null, "type") ?: "node"
                                    val rAddress = parser.getAttributeValue(null, "address") ?: ""
                                    val rSplit = parser.getAttributeValue(null, "split")?.toIntOrNull() ?: 1
                                    val rCustomKey = parser.getAttributeValue(null, "customKey")
                                    val rCustomValue = parser.getAttributeValue(null, "customValue")
                                    val rFee = parser.getAttributeValue(null, "fee")?.toBooleanStrictOrNull() ?: false
                                    if (rAddress.isNotBlank()) {
                                        currentRecipients.add(ValueRecipient(rName, rType, rAddress, rSplit, rCustomKey, rCustomValue, rFee))
                                    }
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when {
                        name == "value" -> {
                            insideValue = false
                            if (currentRecipients.isNotEmpty()) {
                                val pVal = PodcastValue(currentValueType, currentValueMethod, currentValueSuggested, currentRecipients.toList())
                                val json = pVal.toJson()
                                if (insideItem) {
                                    itemValueJson = json
                                } else {
                                    channelValueJson = json
                                }
                            }
                        }
                        name == "item" || name == "liveitem" -> {
                            insideItem = false
                            val isLiveTag = (name == "liveitem")
                            // Podcasting 2.0 Live Broadcast Rule:
                            // If this is a liveitem tag that has ended, is scheduled for the future, or lacks an active live status,
                            // do NOT create an episode for it. Only genuinely active live broadcasts are emitted.
                            if (isLiveTag && !itemIsLive) {
                                // Live broadcast is not actively airing right now (ended or pending)
                            } else if (itemIsLive || episodes.size < MAX_RAW_ITEMS_PER_FEED) {
                                val effectiveId = if (itemGuid.isNotBlank()) itemGuid else itemAudioUrl
                                if (effectiveId.isNotBlank() && itemAudioUrl.isNotBlank()) {
                                val personsJson = if (itemPersons.isNotEmpty()) {
                                    val arr = JSONArray()
                                    itemPersons.forEach { p ->
                                        arr.put(JSONObject().apply {
                                            put("name", p.name)
                                            p.role?.let { put("role", it) }
                                            p.group?.let { put("group", it) }
                                            p.img?.let { put("img", it) }
                                            p.href?.let { put("href", it) }
                                        })
                                    }
                                    arr.toString()
                                } else null

                                val soundbitesJson = if (itemSoundbites.isNotEmpty()) {
                                    val arr = JSONArray()
                                    itemSoundbites.forEach { s ->
                                        arr.put(JSONObject().apply {
                                            put("startTime", s.startTime)
                                            put("duration", s.duration)
                                            s.title?.let { put("title", it) }
                                        })
                                    }
                                    arr.toString()
                                } else null

                                episodes.add(
                                    Episode(
                                        id = effectiveId,
                                        podcastId = podcastId,
                                        podcastTitle = channelTitle,
                                        title = if (itemTitle.isNotBlank()) itemTitle else if (itemIsLive) "Live Stream Broadcast" else "Untitled Episode",
                                        audioUrl = itemAudioUrl,
                                        publishedAt = itemPubDate,
                                        durationSeconds = itemDurationSec,
                                        description = itemDescription,
                                        artworkUrl = itemArtworkUrl ?: channelArtworkUrl,
                                        transcriptUrl = itemTranscriptUrl,
                                        transcriptType = itemTranscriptType,
                                        chaptersUrl = itemChaptersUrl,
                                        personsJson = personsJson,
                                        soundbitesJson = soundbitesJson,
                                        isLive = itemIsLive,
                                        liveStatus = itemLiveStatus,
                                        fundingUrl = itemFundingUrl ?: channelFundingUrl,
                                        valueJson = itemValueJson ?: channelValueJson
                                    )
                                )
                            }
                        }
                    }
                    name == "channel" -> {
                            insideChannel = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val effectiveCat = effectiveCategory(channelTitle, channelCategory)
        val inferredStrategy = inferPlayOrderStrategy(channelTitle, channelDescription, channelItunesType, effectiveCat)

        val boundedEpisodes = if (episodes.size > MAX_EPISODES_PER_FEED &&
            inferredStrategy != com.eagertolisten.app.data.model.PlayOrderStrategy.CHRONOLOGICAL.name
        ) {
            val (liveItems, standardItems) = episodes.partition { it.isLive }
            liveItems + standardItems.sortedByDescending { it.publishedAt }.take(MAX_EPISODES_PER_FEED)
        } else {
            episodes
        }

        val podcast = Podcast(
            id = podcastId,
            title = if (channelTitle.isNotBlank()) channelTitle else "Untitled Podcast",
            feedUrl = feedUrl,
            artworkUrl = channelArtworkUrl,
            author = channelAuthor,
            description = channelDescription,
            fundingUrl = channelFundingUrl,
            fundingMessage = channelFundingMessage,
            valueJson = channelValueJson,
            category = effectiveCat,
            playOrderStrategy = inferredStrategy
        )

        return ParsedFeed(podcast, boundedEpisodes)
    }

    companion object {
        private const val MAX_EPISODE_DESCRIPTION_CHARS = 8_000
        private const val MAX_CHANNEL_DESCRIPTION_CHARS = 4_000
        private const val MAX_EPISODES_PER_FEED = 200
        private const val MAX_RAW_ITEMS_PER_FEED = 500

        fun effectiveCategory(title: String, rawCategory: String = ""): String {
            val t = title.lowercase()
            return when {
                t.contains("no agenda") -> "News, Current Events"
                t.contains("eager to be healthy") -> "Health & Wellness, Self-Help"
                rawCategory.isNotBlank() -> rawCategory
                else -> "General"
            }
        }

        fun inferPlayOrderStrategy(
            title: String,
            description: String = "",
            itunesType: String = "",
            category: String = ""
        ): String {
            val combined = "$title $description $category".lowercase()
            return when {
                combined.contains("no agenda") -> {
                    com.eagertolisten.app.data.model.PlayOrderStrategy.NEWS.name
                }
                combined.contains("eager to be healthy") -> {
                    com.eagertolisten.app.data.model.PlayOrderStrategy.EVERGREEN.name
                }
                itunesType == "serial" || combined.contains("story") || combined.contains("course") || 
                combined.contains("class") || combined.contains("lecture") || combined.contains("season ") || 
                combined.contains("history of") || combined.contains("audiobook") -> {
                    com.eagertolisten.app.data.model.PlayOrderStrategy.CHRONOLOGICAL.name
                }
                combined.contains("news") || combined.contains("daily") || combined.contains("briefing") || 
                combined.contains("politics") || combined.contains("current events") || combined.contains("today in") -> {
                    com.eagertolisten.app.data.model.PlayOrderStrategy.NEWS.name
                }
                else -> {
                    // When unsure, default to Evergreen / Knowledge
                    com.eagertolisten.app.data.model.PlayOrderStrategy.EVERGREEN.name
                }
            }
        }
    }

    private fun parseDate(dateStr: String): Long {
        val trimmed = dateStr.trim()
        val formats = rfc822FormatsThreadLocal.get() ?: return System.currentTimeMillis()
        for (format in formats) {
            try {
                return format.parse(trimmed)?.time ?: continue
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    private fun parseDuration(durationStr: String): Long {
        val trimmed = durationStr.trim()
        // If integer seconds: e.g. "3600"
        trimmed.toLongOrNull()?.let { return it }

        // Format HH:MM:SS or MM:SS
        val parts = trimmed.split(":")
        try {
            return when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble().toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toDouble().toLong()
                else -> 0L
            }
        } catch (_: Exception) {
            return 0L
        }
    }

    private fun parseIsoDate(dateStr: String): Long {
        val trimmed = dateStr.trim()
        val formats = isoFormatsThreadLocal.get()
        if (formats != null) {
            for (format in formats) {
                try {
                    return format.parse(trimmed)?.time ?: continue
                } catch (_: Exception) {}
            }
        }
        return parseDate(trimmed)
    }
}
