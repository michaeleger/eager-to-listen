package com.eagertolisten.app.importer

/**
 * Last-resort OPML reader for files a strict XML parser rejects. Podverse's "Export OPML" has a
 * known bug (podverse/podverse#333): feed URLs that contain quotes are written unescaped, e.g.
 *   xmlUrl="https://example.com/feed?name="My show""
 * which breaks the whole file. This pulls each <outline>'s text/title and xmlUrl out of the raw
 * text, treating an attribute value as ending at a quote that is followed by another attribute
 * or the end of the tag, so inner quotes survive.
 */
object OpmlRecovery {

    data class Outline(val title: String, val xmlUrl: String)

    private val outlineRegex = Regex("""<outline\b(.*?)/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun extractOutlines(raw: String): List<Outline> {
        val results = mutableListOf<Outline>()
        for (m in outlineRegex.findAll(raw)) {
            val attrs = m.groupValues[1]
            val url = attribute(attrs, "xmlUrl") ?: attribute(attrs, "url") ?: continue
            if (url.isBlank()) continue
            val title = attribute(attrs, "text") ?: attribute(attrs, "title") ?: "Podcast"
            results.add(Outline(unescape(title), unescape(url).trim()))
        }
        return results.distinctBy { it.xmlUrl }
    }

    private fun attribute(attrs: String, name: String): String? {
        // value runs to the last quote before the next ` attr=` or the end of the tag
        val r = Regex("""(?:^|\s)$name\s*=\s*"(.*?)"(?=\s+[A-Za-z_:][\w:.-]*\s*=|\s*/?\s*$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        r.find(attrs)?.let { return it.groupValues[1] }
        val single = Regex("""(?:^|\s)$name\s*=\s*'(.*?)'(?=\s+[A-Za-z_:][\w:.-]*\s*=|\s*/?\s*$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return single.find(attrs)?.groupValues?.get(1)
    }

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
