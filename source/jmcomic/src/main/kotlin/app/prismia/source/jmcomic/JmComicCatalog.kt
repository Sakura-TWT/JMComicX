package app.prismia.source.jmcomic

import app.prismia.comic.ComicCatalogErrorKind
import app.prismia.comic.ComicCatalogException
import app.prismia.comic.ComicCatalogOperation
import app.prismia.comic.ComicPage
import app.prismia.comic.ComicWork
import app.prismia.comic.PagedComicCatalog
import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.plus.core.api.AlbumApi
import app.prismia.plus.core.api.AlbumDetail
import app.prismia.plus.core.api.AlbumSummary
import app.prismia.plus.core.api.SearchPage
import app.prismia.plus.core.protocol.JmId
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.runtime.JmxCore

/**
 * Narrow protocol seam used by [JmComicCatalog]. It keeps the adapter testable
 * without constructing the complete network runtime and makes the dependency
 * direction explicit: the source owns the mapping, while the core owns HTTP.
 */
interface JmAlbumGateway {
    suspend fun search(query: String, page: Int): JmxResult<SearchPage>

    suspend fun detailFull(albumId: String): JmxResult<AlbumDetail>
}

private class CoreAlbumGateway(
    private val api: AlbumApi,
) : JmAlbumGateway {
    override suspend fun search(query: String, page: Int): JmxResult<SearchPage> =
        api.search(query = query, page = page)

    override suspend fun detailFull(albumId: String): JmxResult<AlbumDetail> =
        api.detailFull(albumId)

}

/**
 * Domain adapter for the mature JMComicX protocol implementation.
 *
 * The domain contract deliberately has no JMX types. Failures therefore get
 * translated to [ComicCatalogException] at this boundary, while IDs are
 * canonicalized before they become stable [ContentKey] values.
 */
class JmComicCatalog(
    private val albums: JmAlbumGateway,
) : PagedComicCatalog {
    constructor(core: JmxCore) : this(CoreAlbumGateway(core.albumApi))

    override suspend fun find(query: String): List<ComicWork> =
        searchResolved(query = query, page = 1).items

    override suspend fun searchPage(query: String, page: Int): ComicPage = searchResolved(query, page)

    private suspend fun searchResolved(query: String, page: Int): ComicPage {
        require(page >= 1) { "JM comic pages are one-based" }
        val searchPage = when (val result = albums.search(query, page)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> throw result.error.toException(ComicCatalogOperation.SEARCH)
        }
        val redirectId = searchPage.redirectAlbumId?.trim()?.takeIf { it.isNotEmpty() }
        // Validate the redirect before the detail request. This prevents a
        // malformed server value from reaching the permissive core ID parser.
        if (redirectId != null && parseResponseId(redirectId) == null) {
            throw invalidResponseId(
                operation = ComicCatalogOperation.SEARCH,
                field = "redirect id",
                value = redirectId,
            )
        }
        if (redirectId == null) return searchPage.toPage(page, ComicCatalogOperation.SEARCH)
        return when (val detail = albums.detailFull(redirectId)) {
            is JmxResult.Success -> ComicPage(
                total = 1,
                page = page,
                hasMore = false,
                items = listOf(detail.value.summary.toWork(ComicCatalogOperation.SEARCH)),
            )
            is JmxResult.Failure -> throw detail.error.toException(ComicCatalogOperation.SEARCH)
        }
    }

    override suspend fun detail(key: ContentKey): ComicWork {
        require(key.contentType == ContentType.COMIC) { "JmComicCatalog requires a comic key" }
        require(key.source == ContentSource.JM_COMIC) { "JmComicCatalog requires a JM_COMIC key" }
        val canonicalId = when (val parsed = JmId.parse(key.remoteId)) {
            is JmxResult.Success -> parsed.value
            is JmxResult.Failure -> throw parsed.error.toException(ComicCatalogOperation.DETAIL)
        }
        return when (val result = albums.detailFull(canonicalId)) {
            is JmxResult.Success -> result.value.toWork(ComicCatalogOperation.DETAIL)
            is JmxResult.Failure -> throw result.error.toException(ComicCatalogOperation.DETAIL)
        }
    }

    private fun SearchPage.toPage(page: Int, operation: ComicCatalogOperation): ComicPage {
        redirectAlbumId?.takeIf { it.isNotBlank() }?.let { redirect ->
            if (parseResponseId(redirect) == null) {
                throw invalidResponseId(operation, "redirect id", redirect)
            }
        }
        val items = content.map { it.toWork(operation) }
        val totalCount = total
        // The JM response does not expose page size separately. A calculation
        // such as `page * items.size < total` becomes wrong on a short final
        // page, so stay conservative after page one and stop only on an empty
        // page (or when page one already contains the complete total).
        val hasMore = when {
            items.isEmpty() -> false
            totalCount == null -> true
            page == 1 -> items.size < totalCount
            else -> true
        }
        return ComicPage(items = items, page = page, hasMore = hasMore, total = totalCount)
    }

    private fun invalidResponseId(
        operation: ComicCatalogOperation,
        field: String,
        value: String,
    ) = ComicCatalogException(
        operation = operation,
        kind = ComicCatalogErrorKind.DATA_FORMAT,
        retryable = false,
        message = "JM response contained an invalid $field: $value",
    )

    private fun AlbumSummary.toWork(operation: ComicCatalogOperation): ComicWork {
        val canonicalId = parseResponseId(id)
            ?: throw invalidResponseId(operation, "album id", id)
        return ComicWork(
            key = ContentKey(ContentType.COMIC, ContentSource.JM_COMIC, canonicalId),
            title = name.orEmpty(),
            author = author,
            coverUrl = image,
        )
    }

    /**
     * Response IDs should be strict. [JmId] also accepts arbitrary strings
     * ending in four digits for user-pasted URLs, which is useful at the input
     * boundary but would turn malformed server data such as `broken-1234` into
     * a different, seemingly valid album.
     */
    private fun parseResponseId(raw: String): String? {
        val text = raw.trim()
        val isExplicitId = text.all(Char::isDigit) ||
            JM_PREFIX_ID_PATTERN.matches(text) ||
            ALBUM_URL_ID_PATTERN.matches(text) ||
            QUERY_ID_PATTERN.matches(text)
        return if (isExplicitId) JmId.parseOrNull(text) else null
    }

    private fun AlbumDetail.toWork(operation: ComicCatalogOperation): ComicWork =
        summary.toWork(operation)

    private fun JmxError.toException(operation: ComicCatalogOperation): ComicCatalogException {
        val (kind, code) = when (this) {
            is JmxError.Network -> ComicCatalogErrorKind.NETWORK to null
            is JmxError.Http -> ComicCatalogErrorKind.HTTP to code
            is JmxError.Api -> ComicCatalogErrorKind.REMOTE_API to code
            is JmxError.Decode,
            is JmxError.Schema -> ComicCatalogErrorKind.DATA_FORMAT to null
            is JmxError.EmptyData -> ComicCatalogErrorKind.EMPTY_DATA to null
            is JmxError.Domain -> ComicCatalogErrorKind.DOMAIN to null
            is JmxError.Unknown -> ComicCatalogErrorKind.UNKNOWN to null
        }
        return ComicCatalogException(
            operation = operation,
            kind = kind,
            remoteCode = code,
            retryable = retryable,
            message = message,
            cause = cause,
        )
    }

    private companion object {
        private val JM_PREFIX_ID_PATTERN = Regex("(?i)^jm\\s*\\d+$")
        private val ALBUM_URL_ID_PATTERN = Regex("(?i)^.*/(?:album|photo)/\\d+(?:[/?#].*)?$")
        private val QUERY_ID_PATTERN = Regex("(?i)^.*[?&]id=\\d+.*$")
    }
}
