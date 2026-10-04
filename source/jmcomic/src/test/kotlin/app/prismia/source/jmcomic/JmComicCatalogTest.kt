package app.prismia.source.jmcomic

import app.prismia.comic.ComicCatalogErrorKind
import app.prismia.comic.ComicCatalogException
import app.prismia.comic.ComicCatalogOperation
import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.plus.core.api.AlbumDetail
import app.prismia.plus.core.api.AlbumSummary
import app.prismia.plus.core.api.SearchPage
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmComicCatalogTest {
    @Test
    fun pagedExactIdSearchResolvesTheSameRedirectAsFind() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Success(SearchPage(null, "JM987", emptyList())),
            detailResult = JmxResult.Success(albumDetail("987", "exact")),
        )
        for (pageIndex in listOf(1, 3)) {
            val page = JmComicCatalog(gateway).searchPage("JM987", pageIndex)
            assertEquals(listOf("987"), page.items.map { it.key.remoteId })
            assertEquals(1, page.total)
            assertEquals(pageIndex, page.page)
            assertFalse(page.hasMore)
        }
    }

    @Test
    fun searchPageCanonicalizesIdsAndReportsProtocolPagination() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Success(
                SearchPage(
                    total = 5,
                    redirectAlbumId = null,
                    content = listOf(
                        AlbumSummary("JM123", "first", "author", 10, "cover.jpg"),
                        AlbumSummary("456", "second", null, null)
                    )
                )
            )
        )

        val page = JmComicCatalog(gateway).searchPage("query", page = 1)

        assertEquals(1, gateway.lastSearchPage)
        assertEquals(listOf("123", "456"), page.items.map { it.key.remoteId })
        assertEquals("cover.jpg", page.items.first().coverUrl)
        assertTrue(page.hasMore)
        assertEquals(5, page.total)
    }

    @Test
    fun nonEmptyShortPageStaysConservativeWhenProtocolOmitsPageSize() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Success(
                SearchPage(
                    total = 5,
                    redirectAlbumId = null,
                    content = listOf(AlbumSummary("5", "last", null, null))
                )
            )
        )

        val page = JmComicCatalog(gateway).searchPage("query", page = 3)

        // A later short page may be the final page, but without a protocol
        // page-size field we must fetch once more to observe the empty page.
        assertTrue(page.hasMore)
    }

    @Test
    fun findResolvesExactIdRedirectThroughDetail() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Success(
                SearchPage(total = null, redirectAlbumId = "JM987", content = emptyList())
            ),
            detailResult = JmxResult.Success(albumDetail("987", "exact"))
        )

        val result = JmComicCatalog(gateway).find("JM987")

        assertEquals(listOf("987"), result.map { it.key.remoteId })
        assertEquals("JM987", gateway.lastDetailId)
    }

    @Test
    fun failuresKeepOperationKindStatusAndRetryability() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Failure(
                JmxError.Http(code = 503, message = "temporarily unavailable", retryable = true)
            )
        )

        val failure = runCatching { JmComicCatalog(gateway).searchPage("query") }.exceptionOrNull()

        assertTrue(failure is ComicCatalogException)
        failure as ComicCatalogException
        assertEquals(ComicCatalogOperation.SEARCH, failure.operation)
        assertEquals(ComicCatalogErrorKind.HTTP, failure.kind)
        assertEquals(503, failure.remoteCode)
        assertTrue(failure.retryable)
        assertEquals("temporarily unavailable", failure.message)
    }

    @Test
    fun detailCanonicalizesAliasesBeforeCallingProtocol() = runBlocking {
        val gateway = FakeGateway(
            detailResult = JmxResult.Failure(JmxError.Api(code = 404, message = "missing"))
        )
        val key = ContentKey(ContentType.COMIC, ContentSource.JM_COMIC, "https://example.test/album/123")

        val failure = runCatching { JmComicCatalog(gateway).detail(key) }.exceptionOrNull()

        assertEquals("123", gateway.lastDetailId)
        assertTrue(failure is ComicCatalogException)
        failure as ComicCatalogException
        assertEquals(ComicCatalogOperation.DETAIL, failure.operation)
        assertEquals(ComicCatalogErrorKind.REMOTE_API, failure.kind)
        assertEquals(404, failure.remoteCode)
        assertFalse(failure.retryable)
    }

    @Test
    fun pageZeroIsRejectedInsteadOfBeingSilentlyClampedByCore() = runBlocking {
        val failure = runCatching { JmComicCatalog(FakeGateway()).searchPage("query", page = 0) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun malformedRemoteIdsBecomeDataFormatFailures() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Success(
                    SearchPage(null, null, listOf(AlbumSummary("broken-id-1234", "bad", null, null)))
            )
        )

        val failure = runCatching { JmComicCatalog(gateway).searchPage("query") }.exceptionOrNull()

        assertTrue(failure is ComicCatalogException)
        failure as ComicCatalogException
        assertEquals(ComicCatalogErrorKind.DATA_FORMAT, failure.kind)
        assertEquals(ComicCatalogOperation.SEARCH, failure.operation)
    }

    @Test
    fun malformedRedirectIdsDoNotTriggerASecondDetailRequest() = runBlocking {
        val gateway = FakeGateway(
            searchResult = JmxResult.Success(
                SearchPage(null, "broken-id-1234", emptyList())
            )
        )

        val failure = runCatching { JmComicCatalog(gateway).find("query") }.exceptionOrNull()

        assertTrue(failure is ComicCatalogException)
        assertEquals(null, gateway.lastDetailId)
        failure as ComicCatalogException
        assertEquals(ComicCatalogErrorKind.DATA_FORMAT, failure.kind)
    }

    private class FakeGateway(
        private val searchResult: JmxResult<SearchPage> = JmxResult.Success(SearchPage(null, null, emptyList())),
        private val detailResult: JmxResult<AlbumDetail> = JmxResult.Failure(JmxError.Api(404, "missing")),
    ) : JmAlbumGateway {
        var lastSearchPage: Int? = null
        var lastDetailId: String? = null

        override suspend fun search(query: String, page: Int): JmxResult<SearchPage> {
            lastSearchPage = page
            return searchResult
        }

        override suspend fun detailFull(albumId: String): JmxResult<AlbumDetail> {
            lastDetailId = albumId
            return detailResult
        }
    }

    private fun albumDetail(id: String, name: String) = AlbumDetail(
        id = id,
        name = name,
        description = null,
        authors = listOf("author"),
        imageCount = 3,
        totalViews = null,
        likes = null,
        commentTotal = null,
        tags = emptyList(),
        actors = emptyList(),
        works = emptyList(),
        isFavorite = null,
        liked = null,
        related = emptyList(),
        series = emptyList(),
        seriesId = null,
        price = null,
        purchased = null,
        raw = emptyMap(),
    )
}
