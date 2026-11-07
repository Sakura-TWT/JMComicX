package dev.jmx.client

import dev.jmx.client.core.api.AlbumSummary
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFeedTest {
    private fun album(id: String, title: String = id) = AlbumSummary(id, title, null, 1).toHomeAlbum("https://images.invalid")
    private fun page(vararg ids: String) = DiscoveryPage(ids.map { album(it) }, null)
    private val query = DiscoveryQuery.Ranking(RankingPeriod.WEEK, "0")
    private suspend fun idle(controller: DiscoveryFeedController) = withTimeout(5_000) { controller.state.first { it.query != null && !it.loading } }

    @Test fun mergeKeepsServerOrderDeduplicatesAndRetainsOriginalPosition() {
        val first = mergeDiscoveryPage(DiscoveryFeedState(query), page("2", "1", "2"))
        val next = mergeDiscoveryPage(first, page("1", "3"))
        assertEquals(listOf("2", "1", "3"), next.albums.map { it.id })
        assertEquals(3, next.nextPage)
        assertNull(next.end)
    }
    @Test fun repeatedPageStopsWithoutPretendingTheKnownTotalWasReached() {
        val first = mergeDiscoveryPage(DiscoveryFeedState(query), DiscoveryPage(listOf(album("1")), 10))
        val repeated = mergeDiscoveryPage(first, DiscoveryPage(listOf(album("1")), 10))
        assertEquals(DiscoveryEnd.NO_PROGRESS, repeated.end)
        assertEquals(1, repeated.albums.size)
    }
    @Test fun itemAndPageLimitsAreEnforced() {
        assertEquals(3, mergeDiscoveryPage(DiscoveryFeedState(query), page("1", "2", "3", "4"), itemLimit = 3).albums.size)
        assertEquals(DiscoveryEnd.ITEM_LIMIT, mergeDiscoveryPage(DiscoveryFeedState(query), page("1", "2", "3", "4"), itemLimit = 3).end)
        assertEquals(DiscoveryEnd.PAGE_LIMIT, mergeDiscoveryPage(DiscoveryFeedState(query, nextPage = 2), page("1"), pageLimit = 2).end)
    }
    @Test fun firstPageReplacementDoesNotAppendOldResults() {
        val old = mergeDiscoveryPage(DiscoveryFeedState(query), page("1", "2"))
        val refreshed = mergeDiscoveryPage(old.copy(nextPage = 1), page("3"))
        assertEquals(listOf("3"), refreshed.albums.map { it.id })
        assertEquals(2, refreshed.nextPage)
    }
    @Test fun explicitEmptyPageAndZeroTotalAreAcceptedButContradictoryFirstPageIsNot() {
        val empty = mergeDiscoveryPage(DiscoveryFeedState(query), DiscoveryPage(emptyList(), 0))
        assertTrue(empty.firstLoaded)
        assertEquals(DiscoveryEnd.EMPTY, empty.end)
        val wrong = mergeDiscoveryPage(DiscoveryFeedState(query), DiscoveryPage(emptyList(), 5))
        assertFalse(wrong.firstLoaded)
        assertNotNull(wrong.error)
        assertEquals(1, wrong.nextPage)
    }
    @Test fun rankingPeriodsAndCategoriesAreUniqueAndProtocolBacked() {
        assertEquals(listOf("t", "w", "m", "a"), RankingPeriod.entries.map { it.value })
        assertEquals("0", rankingCategories.first().id)
        assertEquals(rankingCategories.size, rankingCategories.map { it.id }.distinct().size)
        assertEquals("每周必看", JmxRoute.WEEKLY.title)
        assertEquals("排行榜", JmxRoute.RANKING.title)
    }
    @Test fun lateCancelledResponseCannotReplaceNewFilter() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nextQuery = DiscoveryQuery.Ranking(RankingPeriod.MONTH, "0")
        val controller = DiscoveryFeedController(scope, { key, _, _ ->
            if (key == query) { entered.complete(Unit); withContext(NonCancellable) { release.await() }; JmxResult.Success(page("1")) }
            else JmxResult.Success(page("2"))
        })
        try {
            controller.select(query)
            withTimeout(5_000) { entered.await() }
            controller.select(nextQuery)
            assertEquals(listOf("2"), idle(controller).albums.map { it.id })
            release.complete(Unit)
            yield()
            assertEquals(nextQuery, controller.state.value.query)
            assertEquals(listOf("2"), controller.state.value.albums.map { it.id })
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }
    @Test fun failedPageDoesNotAdvanceAndExplicitRetryUsesSamePage() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val requested = mutableListOf<Int>()
        var fail = true
        val controller = DiscoveryFeedController(scope, { _, p, _ ->
            requested.add(p)
            if (p == 2 && fail) JmxResult.Failure(JmxError.Network("offline")) else JmxResult.Success(page(p.toString()))
        })
        try {
            controller.select(query); idle(controller)
            controller.loadMore(); val failed = idle(controller)
            assertEquals(2, failed.nextPage)
            assertEquals(listOf("1"), failed.albums.map { it.id })
            assertNotNull(failed.error)
            controller.loadMore(); yield()
            assertEquals(listOf(1, 2), requested)
            fail = false; controller.retry(); idle(controller)
            assertEquals(listOf(1, 2, 2), requested)
            assertEquals(listOf("1", "2"), controller.state.value.albums.map { it.id })
        } finally { controller.close(); scope.cancel() }
    }
    @Test fun refreshFailureRetainsDataAndSuccessfulRetryReplacesIt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var fail = false
        var refreshed = false
        val controller = DiscoveryFeedController(scope, { _, _, fresh ->
            if (fail) JmxResult.Failure(JmxError.Network("offline")) else JmxResult.Success(page(if (fresh && refreshed) "2" else "1"))
        })
        try {
            controller.select(query); idle(controller)
            fail = true; controller.refresh(); val failed = idle(controller)
            assertEquals(listOf("1"), failed.albums.map { it.id })
            assertEquals(1, failed.nextPage)
            assertNotNull(failed.error)
            fail = false; refreshed = true; controller.retry(); idle(controller)
            assertEquals(listOf("2"), controller.state.value.albums.map { it.id })
        } finally { controller.close(); scope.cancel() }
    }
    @Test fun repeatedLoadMoreSignalsCoalesce() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val controller = DiscoveryFeedController(scope, { _, p, _ -> calls.add(p); if (p == 2) gate.await(); JmxResult.Success(page(p.toString())) })
        try {
            controller.select(query); idle(controller)
            repeat(20) { controller.loadMore() }
            yield()
            assertEquals(listOf(1, 2), calls)
            gate.complete(Unit); idle(controller)
            assertEquals(3, controller.state.value.nextPage)
        } finally { gate.complete(Unit); controller.close(); scope.cancel() }
    }
    @Test fun forcedRefreshAlsoBypassesCacheForSubsequentPages() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val controller = DiscoveryFeedController(scope, { _, p, fresh -> calls.add(p to fresh); JmxResult.Success(page(p.toString())) })
        try {
            controller.select(query); idle(controller)
            controller.refresh(); idle(controller)
            controller.loadMore(); idle(controller)
            assertEquals(listOf(1 to false, 1 to true, 2 to true), calls)
        } finally { controller.close(); scope.cancel() }
    }
    @Test fun timeoutClearsLoadingWithoutClaimingSuccess() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = DiscoveryFeedController(scope, { _, _, _ -> awaitCancellation() }, timeoutMillis = 50)
        try {
            controller.select(query)
            val state = idle(controller)
            assertFalse(state.firstLoaded)
            assertNotNull(state.error)
            assertEquals(1, state.nextPage)
        } finally { controller.close(); scope.cancel() }
    }
    @Test fun cancelledScopeAndClosedControllerCannotLeaveBusyOrRestartRequests() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val controller = DiscoveryFeedController(scope, { _, _, _ -> calls++; awaitCancellation() })
        controller.select(query); yield(); scope.cancel()
        withTimeout(5_000) { controller.state.first { !it.loading } }
        controller.close(); controller.refresh(); controller.select(DiscoveryQuery.Weekly("1", "2")); yield()
        assertEquals(1, calls)
        assertFalse(controller.state.value.loading)
    }
}
