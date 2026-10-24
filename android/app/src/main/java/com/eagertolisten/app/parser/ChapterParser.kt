package com.eagertolisten.app.parser

import com.eagertolisten.app.data.model.Chapter
import org.json.JSONObject

class ChapterParser {

    fun parse(jsonString: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        try {
            val root = JSONObject(jsonString)
            val array = root.optJSONArray("chapters") ?: return emptyList()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val startTime = obj.optDouble("startTime", 0.0)
                val endTime = if (obj.has("endTime")) obj.optDouble("endTime") else null
                val title = obj.optString("title", "Chapter ${i + 1}")
                val img = obj.optString("img").ifBlank { null }
                val url = obj.optString("url").ifBlank { null }

                chapters.add(Chapter(startTime, endTime, title, img, url))
            }
        } catch (_: Exception) {}
        return chapters.sortedBy { it.startTime }
    }

    /**
     * Fallback for feeds (like Riverside.fm, YouTube, Libsyn) that format chapters
     * directly into the episode description / shownotes rather than a Podcasting 2.0 JSON URL.
     */
    fun parseFromDescription(description: String?): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()

        val cleanText = description
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)</li>"), "\n")
            .replace(Regex("(?i)</div>"), "\n")
            .replace(Regex("<[^>]*>"), " ")

        val lines = cleanText.lines()
        val rawChapters = mutableListOf<Chapter>()
        // Matches e.g.:
        // "00:00 Welcome"
        // "01:30 - Have you always been healthy?"
        // "(03:08) The year the revenue stopped"
        // "[03:48] Employees first, paycheck last"
        // "1:23:45 Discussion"
        val timeRegex = Regex("""(?:^|[\s•\-\*])[\[\(]?(\d{1,2}:\d{2}(?::\d{2})?)[\]\)]?[\s\-–—:]+(.+)""")

        for (line in lines) {
            val trimmed = line.trim()
            val match = timeRegex.find(trimmed) ?: continue
            val timeStr = match.groupValues[1]
            val titleStr = match.groupValues[2].trim()

            // Skip lines that are just numbers or too short
            if (titleStr.length >= 2 && !titleStr.all { it.isDigit() }) {
                val seconds = parseTimeToSeconds(timeStr)
                rawChapters.add(Chapter(startTime = seconds, endTime = null, title = titleStr))
            }
        }

        // Require at least 2 detected chapter lines to qualify as a valid chapter list
        if (rawChapters.size < 2) return emptyList()

        val sorted = rawChapters.distinctBy { it.startTime }.sortedBy { it.startTime }

        // Populate endTime from the next chapter's startTime
        return sorted.mapIndexed { index, chapter ->
            val nextStart = if (index < sorted.size - 1) sorted[index + 1].startTime else null
            chapter.copy(endTime = nextStart)
        }
    }

    private fun parseTimeToSeconds(timeStr: String): Double {
        val parts = timeStr.split(":")
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: 0.0
                val minutes = parts[1].toDoubleOrNull() ?: 0.0
                val seconds = parts[2].toDoubleOrNull() ?: 0.0
                (hours * 3600.0) + (minutes * 60.0) + seconds
            }
            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: 0.0
                val seconds = parts[1].toDoubleOrNull() ?: 0.0
                (minutes * 60.0) + seconds
            }
            else -> timeStr.toDoubleOrNull() ?: 0.0
        }
    }
}
