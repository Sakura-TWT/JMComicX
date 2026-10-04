package app.prismia.source.oreno

import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.net.URI

/** DOM contracts are derived from source HTML, not from another client. */
class OrenoHtmlParser {
    fun parseCards(html: String): List<OrenoVideoRecord> = cards(document(html))

    fun parsePage(html: String, page: Int): OrenoPage {
        require(page >= 1)
        val document = document(html)
        val items = cards(document)
        val pagination = document.selectFirst("ul.pagination")
        val hasMore = if (pagination == null) {
            items.size == ORENO_PAGE_SIZE
        } else {
            pagination.select("a[href]").any { link ->
                val url = runCatching { URI(link.absUrl("href")) }.getOrNull()
                url?.host == "oreno3d.com" &&
                    (PAGE_QUERY.find(url.rawQuery.orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0) > page
            }
        }
        return OrenoPage(items, page, items.isNotEmpty() && hasMore)
    }

    fun parseDetail(html: String, id: String): OrenoVideoRecord? {
        require(ORENO_MOVIE_ID.matches(id)) { "invalid Oreno movie ID" }
        val document = document(html)
        val heading = document.selectFirst("h1.video-h1") ?: return null
        val title = heading.text().trim().takeIf(String::isNotEmpty)
            ?: throw OrenoParseException("Oreno detail has no title")
        val canonical = document.selectFirst("link[rel=canonical]")?.absUrl("href")?.let(::movieId)
        if (canonical != null && canonical != id) throw OrenoParseException("Oreno detail ID does not match the request")
        val header = heading.closest("header") ?: document
        val playLink = header.select("figure.video-figure a[href]").firstNotNullOfOrNull { iwaraId(it.absUrl("href")) }
        val sections = document.select("section.video-section-tag")
        val author = sections.select("a[href*='/authors/']").firstOrNull()?.cleanText()
        val tags = sections.select("a[href*='/tags/']").map { it.cleanText() }.filter(String::isNotBlank).distinct()
        return OrenoVideoRecord(
            id = id,
            title = title,
            iwaraVideoId = playLink,
            author = author,
            thumbnailUrl = header.selectFirst("img.video-img")?.imageUrl(),
            tags = tags,
            viewCount = header.statistic("remove_red_eye"),
            likeCount = header.statistic("favorite"),
            description = document.selectFirst("blockquote.video-information-comment")?.cleanText(),
        )
    }

    private fun document(html: String): Document {
        if (html.isBlank()) throw OrenoParseException("Oreno returned an empty HTML document")
        return Jsoup.parse(html, BASE_URL)
    }

    private fun cards(document: Document): List<OrenoVideoRecord> {
        val main = document.selectFirst("div.g-main-grid") ?: document.body()
        val articles = main.select("article").filter { it.parents().none { parent -> parent.hasClass("g-main-grid-related") } }
        val records = articles.mapNotNull { article ->
            val link = article.select("a[href]").firstOrNull { movieId(it.absUrl("href")) != null }
            if (link == null) {
                if (EMPTY_MARKERS.any { it in article.text() }) return@mapNotNull null
                throw OrenoParseException("Oreno article has no movie link")
            }
            val title = article.selectFirst("h2.box-h2, h2, h3")?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: link.attr("title").takeIf(String::isNotBlank)
                ?: article.selectFirst("img[alt]")?.attr("alt")?.takeIf(String::isNotBlank)
                ?: throw OrenoParseException("Oreno movie card has no title")
            val image = article.selectFirst("img.main-thumbnail") ?: article.selectFirst("img")
            val author = article.selectFirst("div.box-text1 div.box-text-in")?.cleanText()
                ?: article.attr("data-author").takeIf(String::isNotBlank)
            val tags = article.selectFirst("div.box-text2 div.box-text-in")?.cleanText()
                ?.split(WHITESPACE)?.filter(String::isNotBlank).orEmpty()
            val stats = article.select("div.figure-text-in").map { number(it.text()) }
            OrenoVideoRecord(
                id = requireNotNull(movieId(link.absUrl("href"))),
                title = title,
                iwaraVideoId = article.select("a[href]").firstNotNullOfOrNull { iwaraId(it.absUrl("href")) },
                author = author,
                thumbnailUrl = image?.imageUrl(),
                tags = tags.distinct(),
                viewCount = number(article.attr("data-views")) ?: stats.getOrNull(0),
                likeCount = number(article.attr("data-favorites")) ?: stats.getOrNull(1),
            )
        }.distinctBy(OrenoVideoRecord::id)
        if (articles.isEmpty() && EMPTY_MARKERS.none { it in main.text() }) {
            throw OrenoParseException("Oreno list structure is missing")
        }
        return records
    }

    private fun Element.cleanText(): String = clone().apply { select("i, svg, script, style").remove() }.text().trim()

    private fun Element.statistic(icon: String): Long? = select("i.material-icons")
        .firstOrNull { it.text().trim() == icon }?.nextElementSibling()
        ?.takeIf { it.hasClass("video-text") }?.text()?.let(::number)

    private fun Element.imageUrl(): String? {
        for (attribute in listOf("data-src", "data-original", "src")) {
            val absolute = absUrl(attribute).takeIf(String::isNotBlank) ?: continue
            val uri = runCatching { URI(absolute) }.getOrNull() ?: continue
            if (uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null) return absolute
        }
        return null
    }

    private fun movieId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != "oreno3d.com") return null
        return MOVIE_PATH.matchEntire(uri.path)?.groupValues?.get(1)
    }

    private fun iwaraId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host !in setOf("iwara.tv", "www.iwara.tv") || uri.scheme !in setOf("https", "http")) return null
        return IWARA_PATH.matchEntire(uri.path)?.groupValues?.get(1)
    }

    private fun number(raw: String): Long? {
        val match = NUMBER.matchEntire(raw.trim().replace(",", "")) ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "k" -> BigDecimal(1000)
            "m" -> BigDecimal(1_000_000)
            else -> BigDecimal.ONE
        }
        return runCatching { match.groupValues[1].toBigDecimal().multiply(multiplier).longValueExact() }.getOrNull()
    }

    private companion object {
        const val BASE_URL = "https://oreno3d.com/"
        val MOVIE_PATH = Regex("/movies?/([0-9]{1,20})/?")
        val IWARA_PATH = Regex("/(?:video|v)/([A-Za-z0-9_-]{1,128})(?:/[^/]*)?/?")
        val PAGE_QUERY = Regex("(?:^|&)page=([0-9]+)(?:&|$)")
        val WHITESPACE = Regex("\\s+")
        val NUMBER = Regex("([0-9]+(?:\\.[0-9]+)?)([kKmM]?)")
        val EMPTY_MARKERS = listOf("動画が見つかりません。", "No Movies.")
    }
}

/** The site's fixed page size, independent of the consumer's requested limit. */
internal const val ORENO_PAGE_SIZE = 36
internal val ORENO_MOVIE_ID = Regex("[0-9]{1,20}")

class OrenoParseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() = SourceFailure(
            source = ContentSource.ORENO3D,
            operation = "parse",
            category = SourceErrorCategory.PROTOCOL,
            retryable = false,
            message = checkNotNull(super.message),
            cause = cause,
        )
}
