package dev.jmx.client

import dev.jmx.client.core.api.AlbumSummary
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DiscoverySnapshotTest {
    private val a = DiscoveryQuery.Ranking(RankingPeriod.WEEK, "0")
    private val b = DiscoveryQuery.Ranking(RankingPeriod.MONTH, "0")
    private val c = DiscoveryQuery.Ranking(RankingPeriod.DAY, "0")
    private fun page(vararg ids: String) = DiscoveryPage(ids.map { AlbumSummary(it, it, null, 1).toHomeAlbum("https://images.invalid") }, null)
    private suspend fun idle(c: DiscoveryFeedController) = withTimeout(5_000) { c.state.first { it.query != null && !it.loading } }

    @Test fun revisitRestoresSynchronouslyWithoutNetworkOrLoadingFlash() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val controller = DiscoveryFeedController(scope, { q, _, _ -> calls++; JmxResult.Success(page(if (q == a) "1" else "2")) }, snapshotLimit = 4)
        try {
            controller.select(a); idle(controller); controller.select(b); idle(controller)
            controller.select(a)
            assertFalse(controller.state.value.loading)
            assertEquals(listOf("1"), controller.state.value.albums.map { it.id })
            assertEquals(2, calls)
            assertEquals(2, controller.cachedQueryCount)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun snapshotsPreserveCompletePaginationCursorAndServerOrder() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val calls = mutableListOf<Pair<DiscoveryQuery, Int>>()
        val controller = DiscoveryFeedController(scope, { q, p, _ -> calls.add(q to p); JmxResult.Success(page(p.toString())) }, snapshotLimit = 4)
        try {
            controller.select(a); idle(controller); controller.loadMore(); idle(controller)
            controller.select(b); idle(controller); controller.select(a)
            assertEquals(3, controller.state.value.nextPage)
            assertEquals(listOf("1", "2"), controller.state.value.albums.map { it.id })
            controller.loadMore(); idle(controller)
            assertEquals(listOf(a to 1, a to 2, b to 1, a to 3), calls)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun forcedSelectBypassesSnapshotAndPropagatesToFollowingPage() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val controller = DiscoveryFeedController(scope, { _, p, fresh -> calls.add(p to fresh); JmxResult.Success(page(p.toString())) }, snapshotLimit = 4)
        try {
            controller.select(a); idle(controller); controller.select(b); idle(controller)
            controller.select(a, fresh = true); idle(controller); controller.loadMore(); idle(controller)
            assertEquals(listOf(1 to false, 1 to false, 1 to true, 2 to true), calls)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun ttlAndClockRollbackCannotServeExpiredSnapshots() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var now = 100L
        var calls = 0
        val controller = DiscoveryFeedController(scope, { _, _, _ -> calls++; JmxResult.Success(page("1")) }, snapshotLimit = 4, snapshotTtlMillis = 50, nowMillis = { now })
        try {
            controller.select(a); idle(controller); controller.select(b); idle(controller)
            now = 149; controller.select(a); assertEquals(2, calls)
            controller.select(b); assertEquals(2, calls)
            now = 150; controller.select(a); idle(controller); assertEquals(3, calls)
            now = 99; controller.select(b); idle(controller); assertEquals(4, calls)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun lruAndTotalItemBudgetsEvictWithoutTruncatingCursors() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val controller = DiscoveryFeedController(scope, { _, _, _ -> calls++; JmxResult.Success(page("1", "2")) }, snapshotLimit = 2, snapshotItemLimit = 3)
        try {
            controller.select(a); idle(controller); controller.select(b); idle(controller)
            assertEquals(1, controller.cachedQueryCount)
            assertEquals(2, controller.cachedAlbumCount)
            controller.select(a); idle(controller)
            assertEquals(3, calls)
            assertEquals(2, controller.state.value.albums.size)
            assertEquals(2, controller.state.value.nextPage)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun recentlyUsedQueryWinsLruEviction() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val controller = DiscoveryFeedController(scope, { _, _, _ -> calls++; JmxResult.Success(page("1")) }, snapshotLimit = 2)
        try {
            controller.select(a); idle(controller); controller.select(b); idle(controller)
            controller.select(a); controller.select(c); idle(controller)
            controller.select(a); assertEquals(3, calls)
            controller.select(b); idle(controller); assertEquals(4, calls)
            assertTrue(controller.cachedQueryCount <= 2)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun restoredQueryRejectsALateCancelledDifferentFilter() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = DiscoveryFeedController(scope, { q, _, _ ->
            if (q == b) { entered.complete(Unit); withContext(NonCancellable) { release.await() }; JmxResult.Success(page("2")) }
            else JmxResult.Success(page("1"))
        }, snapshotLimit = 4)
        try {
            controller.select(a); idle(controller); controller.select(b); entered.await()
            controller.select(a)
            assertFalse(controller.state.value.loading)
            release.complete(Unit); yield()
            assertEquals(a, controller.state.value.query)
            assertEquals(listOf("1"), controller.state.value.albums.map { it.id })
            assertEquals(1, controller.cachedQueryCount)
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }

    @Test fun closeReleasesAllSnapshots() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = DiscoveryFeedController(scope, { _, _, _ -> JmxResult.Success(page("1")) }, snapshotLimit = 4)
        try {
            controller.select(a); idle(controller); controller.close()
            assertEquals(0, controller.cachedQueryCount)
            assertEquals(0, controller.cachedAlbumCount)
            controller.select(b); assertEquals(a, controller.state.value.query)
        } finally { scope.cancel() }
    }
}
