package app.prismia.source.oreno

/**
 * Dependency-free parser boundary. Oreno pages are server-rendered HTML and
 * have changed their card markup several times, so this parser first isolates
 * card elements with a small balanced-tag scanner. A single greedy regex would
 * stop at the first nested </div> and silently mix fields from adjacent cards.
 */
class OrenoHtmlParser {
    fun parseCards(html: String): List<OrenoVideoRecord> = cardFragments(html)
        .mapNotNull(::parseRecord)
        .distinctBy { it.id }

    fun parseDetail(html: String, id: String): OrenoVideoRecord? {
        val wantedId = id.trim()
        require(wantedId.isNotEmpty()) { "Oreno id must not be blank" }
        return parseCards(html).firstOrNull { it.id == wantedId }
            ?: DETAIL_ID_PATTERN.find(html)?.takeIf { it.groupValues[1] == wantedId }
                ?.let { parseRecord(html, wantedId) }
    }

    private fun cardFragments(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val fragments = ArrayList<String>()
        val openings = (ARTICLE_OPEN_PATTERN.findAll(html) + CARD_OPEN_PATTERN.findAll(html))
            .sortedBy { it.range.first }
        for (opening in openings) {
            val tagName = opening.groupValues[1]
            val end = matchingElementEnd(html, opening.range.last + 1, tagName)
            fragments += html.substring(opening.range.first, end)
        }
        return fragments
    }

    private fun matchingElementEnd(html: String, contentStart: Int, tagName: String): Int {
        var depth = 1
        val wantedTag = tagName.lowercase()
        for (token in HTML_TOKEN_PATTERN.findAll(html, contentStart)) {
            val raw = token.value
            if (raw.startsWith("<!--")) continue
            val tokenTag = token.groupValues.getOrNull(2)?.lowercase() ?: continue
            if (tokenTag != wantedTag) continue
            if (token.groupValues.getOrNull(1) == "/") {
                depth--
                if (depth == 0) return token.range.last + 1
            } else if (!raw.trimEnd().endsWith("/>") && tokenTag !in VOID_TAGS) {
                depth++
            }
        }
        // Malformed/truncated HTML is common when a proxy cuts a response.
        // Returning the remaining fragment still lets us recover its metadata.
        return html.length
    }

    private fun parseRecord(block: String, forcedId: String? = null): OrenoVideoRecord? {
        val id = forcedId ?: ORENO_ID_PATTERN.find(block)?.groupValues?.getOrNull(2) ?: return null
        val title = TITLE_PATTERN.find(block)?.groupValues?.getOrNull(1)
            ?: HEADING_PATTERN.find(block)?.groupValues?.getOrNull(1)
            ?: ""
        val thumbnail = IMAGE_PATTERN.find(block)?.groupValues?.getOrNull(1)?.decodeHtml()
        val iwaraId = IWARA_PATTERN.find(block)?.groupValues?.getOrNull(1)
        val author = AUTHOR_PATTERN.find(block)?.groupValues?.getOrNull(1)?.decodeHtml()
        return OrenoVideoRecord(
            id = id,
            title = title.decodeHtml().collapseWhitespace(),
            iwaraVideoId = iwaraId,
            author = author,
            thumbnailUrl = thumbnail,
            viewCount = DATA_VIEWS_PATTERN.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull(),
            likeCount = DATA_LIKES_PATTERN.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull(),
        )
    }

    private fun String.decodeHtml(): String =
        replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace(NUMERIC_ENTITY_PATTERN) { match ->
                val digits = match.groupValues[2]
                val radix = if (match.groupValues[1].isNotEmpty()) 16 else 10
                digits.toIntOrNull(radix)?.let { codePoint ->
                    runCatching { String(Character.toChars(codePoint)) }.getOrNull()
                } ?: match.value
            }

    private fun String.collapseWhitespace(): String = trim().replace(WHITESPACE_PATTERN, " ")

    companion object {
        private val CARD_OPEN_PATTERN = Regex(
            "(?is)<(li|div)\\b(?=[^>]*(?:class|id)\\s*=\\s*[\"'][^\"']*(?:movie|video|item)[^\"']*[\"'])[^>]*>",
        )
        private val ARTICLE_OPEN_PATTERN = Regex("(?is)<(article)\\b[^>]*>")
        private val HTML_TOKEN_PATTERN = Regex("(?is)<!--.*?-->|<(/?)([a-z][a-z0-9:-]*)\\b[^>]*>")
        private val ORENO_ID_PATTERN = Regex("(?i)(?:/|[\\\"'])((?:movies?|videos?))/([A-Za-z0-9_-]+)")
        private val DETAIL_ID_PATTERN = Regex("(?i)(?:/|[\\\"'])(?:movies?|videos?)/([A-Za-z0-9_-]+)(?:[/?#\\\"'])")
        private val IWARA_PATTERN = Regex(
            "(?i)(?:https?:)?//(?:www\\.)?iwara\\.tv/(?:video/|v/)?([A-Za-z0-9_-]+)",
        )
        private val TITLE_PATTERN = Regex("(?is)\\b(?:data-title|title|alt)\\s*=\\s*[\"']([^\"']+)[\"']")
        private val HEADING_PATTERN = Regex("(?is)<(?:h1|h2|h3)\\b[^>]*>(.*?)</(?:h1|h2|h3)>")
        private val IMAGE_PATTERN = Regex("(?is)<img\\b[^>]+(?:src|data-src|data-original)\\s*=\\s*[\"']([^\"']+)[\"']")
        private val AUTHOR_PATTERN = Regex("(?is)\\b(?:data-author|author)\\s*=\\s*[\"']([^\"']+)[\"']")
        private val DATA_VIEWS_PATTERN = Regex("(?i)data-views\\s*=\\s*[\"'](\\d+)[\"']")
        private val DATA_LIKES_PATTERN = Regex("(?i)data-(?:favorites|likes)\\s*=\\s*[\"'](\\d+)[\"']")
        private val NUMERIC_ENTITY_PATTERN = Regex("&#(x?)([0-9a-f]+);?", RegexOption.IGNORE_CASE)
        private val WHITESPACE_PATTERN = Regex("\\s+")
        private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    }
}

class OrenoParseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
