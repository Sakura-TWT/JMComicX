package app.prismia.comic

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentRef
import app.prismia.foundation.ContentType

data class ComicWork(
    val key: ContentKey,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
) {
    init {
        require(key.contentType == ContentType.COMIC) { "ComicWork requires a COMIC content key" }
    }
}

/** A page returned by a comic source. JMComic's protocol uses one-based pages. */
data class ComicPage(
    val items: List<ComicWork>,
    val page: Int,
    val hasMore: Boolean,
    val total: Int? = null,
) {
    init {
        require(page >= 1) { "comic pages are one-based" }
        require(total == null || total >= 0) { "total must be non-negative" }
    }
}

interface ComicCatalog {
    suspend fun find(query: String): List<ComicWork>
    suspend fun detail(key: ContentKey): ComicWork
}

/**
 * Optional paging capability. Keeping this separate lets small/offline sources
 * implement [ComicCatalog] without inventing pagination semantics.
 */
interface PagedComicCatalog : ComicCatalog {
    /** [page] is one-based to match the JM API contract. */
    suspend fun searchPage(query: String, page: Int = 1): ComicPage
}

enum class ComicCatalogOperation {
    SEARCH,
    DETAIL,
}

enum class ComicCatalogErrorKind {
    NETWORK,
    HTTP,
    REMOTE_API,
    DATA_FORMAT,
    EMPTY_DATA,
    DOMAIN,
    UNKNOWN,
}

/**
 * Source failures must cross the domain boundary with their retryability and
 * remote status intact. The adapter maps protocol-specific errors to this
 * source-neutral exception instead of returning an indistinguishable empty list.
 */
class ComicCatalogException(
    val operation: ComicCatalogOperation,
    val kind: ComicCatalogErrorKind,
    val remoteCode: Int? = null,
    val retryable: Boolean = false,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

fun ComicWork.asRef(): ContentRef = ContentRef(
    key = key,
    title = title,
    coverUrl = coverUrl,
)
