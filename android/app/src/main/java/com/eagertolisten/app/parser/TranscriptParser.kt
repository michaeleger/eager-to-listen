package com.eagertolisten.app.parser

import com.eagertolisten.app.data.model.TranscriptCue
import org.json.JSONObject

class TranscriptParser {

    fun parse(rawText: String, type: String? = null, durationSeconds: Long = 0): List<TranscriptCue> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        return when {
            type?.contains("json") == true || trimmed.startsWith("{") -> parseJson(trimmed)
            trimmed.startsWith("WEBVTT") || type?.contains("vtt") == true -> parseWebVtt(trimmed)
            trimmed.contains("-->") -> parseSrt(trimmed)
            else -> parsePlainText(trimmed, durationSeconds)
        }
    }

    private fun parseWebVtt(content: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val lines = content.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size >= 2) {
                    val startMs = parseTimestamp(times[0].trim().split(" ")[0])
                    val endMs = parseTimestamp(times[1].trim().split(" ")[0])

                    val textBuilder = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                        textBuilder.append(lines[i].trim())
                        i++
                    }
                    val text = cleanCueText(textBuilder.toString())
                    if (text.isNotBlank()) {
                        cues.add(TranscriptCue(startMs, endMs, text))
                    }
                }
            }
            i++
        }
        return cues
    }

    private fun parseSrt(content: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val blocks = content.replace("\r\n", "\n").split("\n\n")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size >= 2) {
                val timeLineIndex = if (lines[0].contains("-->")) 0 else 1
                if (timeLineIndex < lines.size && lines[timeLineIndex].contains("-->")) {
                    val times = lines[timeLineIndex].split("-->")
                    val startMs = parseTimestamp(times[0].trim())
                    val endMs = parseTimestamp(times[1].trim())
                    val text = lines.drop(timeLineIndex + 1).joinToString(" ")
                    val cleaned = cleanCueText(text)
                    if (cleaned.isNotBlank()) {
                        cues.add(TranscriptCue(startMs, endMs, cleaned))
                    }
                }
            }
        }
        return cues
    }

    private fun parseJson(content: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        try {
            val root = JSONObject(content)
            val segments = root.optJSONArray("segments") ?: root.optJSONArray("transcripts")
            if (segments != null) {
                for (idx in 0 until segments.length()) {
                    val seg = segments.getJSONObject(idx)
                    val startSec = seg.optDouble("startTime", seg.optDouble("start", 0.0))
                    val endSec = seg.optDouble("endTime", seg.optDouble("end", startSec + 2.0))
                    val text = seg.optString("body", seg.optString("text", ""))
                    if (text.isNotBlank()) {
                        cues.add(TranscriptCue((startSec * 1000).toLong(), (endSec * 1000).toLong(), text.trim()))
                    }
                }
            }
        } catch (_: Exception) {}
        return cues
    }

    /**
     * Parses plain text transcripts (like Riverside.fm's text/plain transcripts.txt).
     * Handles timestamped lines if present, or distributes speaker paragraphs proportionally
     * across the episode duration.
     */
    private fun parsePlainText(content: String, durationSeconds: Long): List<TranscriptCue> {
        val rawParagraphs = content.replace("\r\n", "\n").split(Regex("\n\\s*\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawParagraphs.isEmpty()) return emptyList()

        // 1. Check if the text contains embedded timestamps (e.g. "[01:23] Speaker: text" or "01:23:45 Text")
        val timestampRegex = Regex("""^[\[\(]?(\d{1,2}:\d{2}(?::\d{2})?)[\]\)]?[\s\-:]+(.+)""", RegexOption.DOT_MATCHES_ALL)
        val timestampedCues = mutableListOf<TranscriptCue>()

        for (p in rawParagraphs) {
            val match = timestampRegex.find(p)
            if (match != null) {
                val timeStr = match.groupValues[1]
                val text = match.groupValues[2].trim()
                val startMs = parseTimestamp(timeStr)
                timestampedCues.add(TranscriptCue(startMs, startMs + 5000L, cleanCueText(text)))
            }
        }

        if (timestampedCues.size >= 3) {
            // Adjust end times to match the start of the next cue
            return timestampedCues.mapIndexed { index, cue ->
                val nextStart = if (index < timestampedCues.size - 1) timestampedCues[index + 1].startMs else cue.startMs + 5000L
                cue.copy(endMs = nextStart)
            }
        }

        // 2. Proportional paragraph timing for Riverside-style speaker turns:
        val paragraphWords = rawParagraphs.map { p ->
            p.split(Regex("\\s+")).filter { it.isNotBlank() }.size.coerceAtLeast(1)
        }
        val totalWords = paragraphWords.sum().toDouble()
        val totalDurationMs = if (durationSeconds > 0) durationSeconds * 1000.0 else (totalWords / 2.5) * 1000.0

        val cues = mutableListOf<TranscriptCue>()
        var accumulatedWords = 0

        for (i in rawParagraphs.indices) {
            val p = rawParagraphs[i]
            val wordsInP = paragraphWords[i]
            val startMs = ((accumulatedWords / totalWords) * totalDurationMs).toLong()
            accumulatedWords += wordsInP
            val endMs = ((accumulatedWords / totalWords) * totalDurationMs).toLong().coerceAtLeast(startMs + 1000L)

            cues.add(TranscriptCue(startMs, endMs, cleanCueText(p)))
        }

        return cues
    }

    private fun parseTimestamp(timeStr: String): Long {
        try {
            val normalized = timeStr.replace(',', '.')
            val parts = normalized.split(":")
            return when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toDouble()
                    ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toDouble()
                    ((minutes * 60 + seconds) * 1000).toLong()
                }
                else -> (normalized.toDoubleOrNull() ?: 0.0 * 1000).toLong()
            }
        } catch (_: Exception) {
            return 0L
        }
    }

    private fun cleanCueText(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), "").trim()
    }
}
