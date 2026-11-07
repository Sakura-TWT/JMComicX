package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class ComicSearchLogicTest {
    @Test
    fun paginationStopsWhenServerRepeatsTheSameAlbums() {
        val first = HomeAlbum("1", "A", "作者", "cover-1", "https://img.test")
        val second = HomeAlbum("2", "B", "作者", "cover-2", "https://img.test")

        val repeated = mergeSearchAlbums(listOf(first), listOf(first), sourceEndReached = false)
        assertEquals(listOf(first), repeated.albums)
        assertEquals(true, repeated.endReached)

        val extended = mergeSearchAlbums(listOf(first), listOf(first, second), sourceEndReached = false)
        assertEquals(listOf(first, second), extended.albums)
        assertEquals(false, extended.endReached)
    }

    @Test
    fun historyKeepsNewestUniqueQueriesWithinLimit() {
        assertEquals(
            listOf("女性向", "連載中", "JM123"),
            searchHistoryWith(
                current = listOf("連載中", "女性向", "JM123", "其他"),
                query = " 女性向 ",
                limit = 3,
            ),
        )
        assertEquals(
            listOf("jm123", "女性向"),
            searchHistoryWith(listOf("JM123", "女性向"), "jm123"),
        )
    }

    @Test
    fun openCcConvertsSimplifiedQueryToTraditional() {
        assertEquals("連載中與女性向", toTraditionalChinese("连载中与女性向"))
    }

    @Test
    fun queryVariantsIncludeOriginalAndTraditionalWithoutDuplicates() {
        assertEquals(
            listOf("连载中", "連載中"),
            searchQueryVariants("  连载中  ") { value ->
                if (value == "连载中") "連載中" else value
            },
        )
        assertEquals(
            listOf("女性向"),
            searchQueryVariants("女性向") { it },
        )
    }

    @Test
    fun jmIdSearchAcceptsDigitsAndOptionalPrefix() {
        assertEquals("1232836", "1232836".toJmSearchIdOrNull())
        assertEquals("1232836", "JM 1232836".toJmSearchIdOrNull())
        assertEquals("1232836", "jm1232836".toJmSearchIdOrNull())
        assertNull("JM12A".toJmSearchIdOrNull())
        assertNull("作品1232836".toJmSearchIdOrNull())
    }

    @Test
    fun searchOrdersMatchOfficialApiValues() {
        assertEquals(
            listOf("mr", "mv", "mp", "tf"),
            ComicSearchOrder.entries.map { it.apiValue },
        )
        assertEquals(
            listOf("最新", "最多点击", "最多图片", "最多爱心"),
            ComicSearchOrder.entries.map { it.label },
        )
    }

    @Test
    fun firstSubmissionLoadsBeforeAnyGridIsMounted() = runBlocking {
        val submission = SearchSubmissionState(SearchRequestEpoch())
        submission.begin("  本地测试  ", ComicSearchOrder.LATEST, SearchTagFilter())
        assertEquals(ComicSearchUiState.Loading, submission.state)
        var calls = 0
        submission.execute(requireNotNull(submission.request)) { query, page, order, filter ->
            calls++
            assertEquals("本地测试", query)
            assertEquals(1, page)
            assertEquals(ComicSearchOrder.LATEST, order)
            assertFalse(filter.enabled)
            ComicSearchResult.Page(emptyList(), 0, true)
        }
        assertEquals(1, calls)
        assertEquals(ComicSearchUiState.Empty("本地测试"), submission.state)
    }

    @Test
    fun sameQueryRetryInvalidatesLateFirstPageEvenAfterCancellation() = runBlocking {
        val epoch = SearchRequestEpoch()
        val submission = SearchSubmissionState(epoch)
        submission.begin("相同关键词", ComicSearchOrder.LATEST, SearchTagFilter())
        val first = requireNotNull(submission.request)
        val late = CompletableDeferred<ComicSearchResult>()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { submission.execute(first) { _, _, _, _ -> late.await() } }
        }
        task.cancel()
        submission.begin("相同关键词", ComicSearchOrder.LATEST, SearchTagFilter())
        val retry = requireNotNull(submission.request)
        assertFalse(epoch.accepts(first.generation))
        assertTrue(epoch.accepts(retry.generation))
        submission.execute(retry) { _, _, _, _ -> ComicSearchResult.Page(emptyList(), 0, true) }
        late.complete(ComicSearchResult.Error("过期响应"))
        task.join()
        assertEquals(ComicSearchUiState.Empty("相同关键词"), submission.state)
    }

    @Test
    fun querySortAndTagChangesFreezeTheirOwnRequestAndCancelPagination() {
        val epoch = SearchRequestEpoch()
        val submission = SearchSubmissionState(epoch)
        submission.begin("旧词", ComicSearchOrder.LATEST, SearchTagFilter())
        val first = requireNotNull(submission.request)
        val page = Job()
        assertTrue(epoch.register(first.generation, page))
        val filter = SearchTagFilter().toggleInclude("全彩")
        submission.begin("新词", ComicSearchOrder.MOST_VIEWED, filter)
        assertTrue(page.isCancelled)
        assertFalse(epoch.accepts(first.generation))
        assertEquals("旧词", first.query)
        assertEquals(ComicSearchOrder.LATEST, first.order)
        val current = requireNotNull(submission.request)
        assertEquals("新词", current.query)
        assertEquals(ComicSearchOrder.MOST_VIEWED, current.order)
        assertEquals(filter, current.tagFilter)
        submission.reset()
        assertEquals(ComicSearchUiState.Idle, submission.state)
        assertNull(submission.request)
        assertFalse(epoch.accepts(current.generation))
    }

    @Test
    fun paginationHasOneOwnerAndOldCompletionCannotReleaseNewOwner() {
        val epoch = SearchRequestEpoch()
        val generation = epoch.advance()
        val first = Job()
        val duplicate = Job()
        assertTrue(epoch.register(generation, first))
        assertFalse(epoch.register(generation, duplicate))
        assertTrue(duplicate.isCancelled)
        assertFalse(first.isCancelled)
        first.complete()
        epoch.finished(first)
        val second = Job()
        assertTrue(epoch.register(generation, second))
        epoch.finished(first)
        assertFalse(epoch.register(generation, Job()))
        val fresh = epoch.advance()
        assertTrue(second.isCancelled)
        assertFalse(epoch.register(generation, Job()))
        assertTrue(epoch.register(fresh, Job()))
    }

    @Test
    fun firstPageDeduplicatesAndDirectRouteIsTerminal() = runBlocking {
        val album = HomeAlbum("123", "标题", "作者", "", "https://image.invalid")
        val submission = SearchSubmissionState(SearchRequestEpoch())
        submission.begin("标题", ComicSearchOrder.LATEST, SearchTagFilter())
        submission.execute(requireNotNull(submission.request)) { _, _, _, _ ->
            ComicSearchResult.Page(listOf(album, album), 2, false)
        }
        assertEquals(listOf(album), (submission.state as ComicSearchUiState.Content).albums)
        submission.begin("JM123", ComicSearchOrder.LATEST, SearchTagFilter())
        assertEquals(album, submission.execute(requireNotNull(submission.request)) { _, _, _, _ -> ComicSearchResult.Direct(album) })
        assertTrue((submission.state as ComicSearchUiState.Content).endReached)
    }

    @Test
    fun paginationHonorsServerEndAndResultBudget() {
        val albums = (1..3).map { HomeAlbum(it.toString(), "作品", "作者", "", "https://image.invalid") }
        assertTrue(mergeSearchAlbums(albums.take(1), albums.drop(1), true).endReached)
        val capped = mergeSearchAlbums(albums.take(1), albums, false, maxAlbums = 2)
        assertEquals(albums.take(2), capped.albums)
        assertTrue(capped.endReached)
        assertTrue(capped.limitReached)
    }

    @Test
    fun localHistoryMatchingPreservesOrderAndCaseInsensitiveSubstrings() {
        val history = listOf("JM123", "连载中", "jm456", "其他")
        assertEquals(history, matchingSearchHistory(history, "  "))
        assertEquals(listOf("JM123", "jm456"), matchingSearchHistory(history, " jm "))
        assertEquals(emptyList<String>(), matchingSearchHistory(history, "不存在"))
    }

    @Test
    fun tagOnlySubmissionPreservesIncludeExcludeQuerySemantics() = runBlocking {
        val filter = SearchTagFilter().toggleInclude("全彩").toggleExclude("短篇")
        val submission = SearchSubmissionState(SearchRequestEpoch())
        submission.begin("", ComicSearchOrder.MOST_LIKED, filter)
        submission.execute(requireNotNull(submission.request)) { query, page, order, tags ->
            assertEquals("", query)
            assertEquals(1, page)
            assertEquals(ComicSearchOrder.MOST_LIKED, order)
            assertEquals(filter, tags)
            ComicSearchResult.Page(emptyList(), 0, true)
        }
        assertEquals(ComicSearchUiState.Empty("+全彩 -短篇"), submission.state)
    }
}
