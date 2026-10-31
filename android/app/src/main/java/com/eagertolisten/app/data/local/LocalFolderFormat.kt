package com.eagertolisten.app.data.local

/**
 * Local folder shows ("virtual feeds"): a folder of audio files on the device that plays like a
 * podcast. The show's id / feedUrl is "local:" + the folder's tree URI; each file is an episode
 * whose audioUrl is the file's content:// URI.
 *
 * Files in a local folder belong to the user (they copied them there), so Eager to Listen never
 * downloads, deletes, exports or syncs them.
 */
object LocalFolderFormat {
    const val ID_PREFIX = "local:"
    const val CATEGORY = "Local Files"
    const val MISSING_REASON = "LOCAL_FILE_MISSING"

    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac")

    fun isLocalFeed(idOrUrl: String?): Boolean = idOrUrl?.startsWith(ID_PREFIX) == true

    fun feedIdFor(treeUri: String): String = ID_PREFIX + treeUri

    fun treeUriOf(feedId: String): String = feedId.removePrefix(ID_PREFIX)

    fun isAudioFile(name: String?, mimeType: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in audioExtensions
    }

    /** "03 - Synth Zone_Live.mp3" -> "03 - Synth Zone Live" */
    fun titleFromFileName(name: String): String {
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val cleaned = base.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return cleaned.ifEmpty { name }
    }

    /** Folder display name for the show title; falls back to the last path segment of the tree URI. */
    fun showTitle(displayName: String?, treeUri: String): String {
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val decoded = java.net.URLDecoder.decode(treeUri.substringAfterLast('/'), "UTF-8")
        return decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { "Local Files" }
    }

    /** Natural order: "Episode 2" before "Episode 10", case-insensitive. */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ta = tokens(a.lowercase())
        val tb = tokens(b.lowercase())
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]; val y = tb[i]
            val nx = x.toBigIntegerOrNull(); val ny = y.toBigIntegerOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ta.size.compareTo(tb.size)
    }

    private fun tokens(s: String): List<String> = Regex("\\d+|\\D+").findAll(s).map { it.value }.toList()

    /**
     * Publish times that keep filename order: each file gets its own modified time, nudged forward
     * so it's always after the file before it. Played "oldest first", the folder plays in
     * filename order, which is how people number archived episodes.
     * Returns times in the same order as [namesToModified] sorted by [naturalOrder].
     */
    fun publishTimesInNameOrder(namesToModified: List<Pair<String, Long>>): List<Pair<String, Long>> {
        val sorted = namesToModified.sortedWith(compareBy(naturalOrder) { it.first })
        var previous = Long.MIN_VALUE
        return sorted.map { (name, modified) ->
            val base = if (modified > 0L) modified else 1L
            val t = if (previous == Long.MIN_VALUE) base else maxOf(base, previous + 1L)
            previous = t
            name to t
        }
    }
}
