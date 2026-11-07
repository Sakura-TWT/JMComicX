package dev.jmx.client

import dev.jmx.client.core.api.AlbumSummary
import dev.jmx.client.core.api.WeekCategory
import dev.jmx.client.core.api.WeekInfo
import dev.jmx.client.core.api.WeekType
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class WeeklyDiscoveryCoordinatorTest {
    private fun catalog(week: String = "w1", types: List<String> = listOf("t1", "t2")) = WeekInfo(
        listOf(WeekCategory(week, "日期", "期刊")), types.map { WeekType(it, it) }, emptyMap(),
    )
    private fun page(id: String) = DiscoveryPage(listOf(AlbumSummary(id, id, null, 1).toHomeAlbum("https://images.invalid")), null)
    private suspend fun idle(c: WeeklyDiscoveryCoordinator) {
        withTimeout(5_000) { c.state.first { !it.catalogLoading }; c.feed.state.first { !it.loading }; yield() }
    }

    @Test fun coldEntryLoadsCatalogThenExactlyOneFirstPage() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val order = mutableListOf<String>()
        val c = WeeklyDiscoveryCoordinator(scope, { fresh -> order.add("catalog:$fresh"); JmxResult.Success(catalog()) }, { q, p, fresh -> order.add("page:$p:$fresh"); assertEquals(DiscoveryQuery.Weekly("w1", "t1"), q); JmxResult.Success(page("1")) })
        try {
            c.activate(); idle(c)
            assertEquals(listOf("catalog:false", "page:1:false"), order)
            repeat(10) { c.activate() }; yield()
            assertEquals(2, order.size)
        } finally { c.close(); scope.cancel() }
    }

    @Test fun knownCatalogLetsContentLoadWhileMetadataIsStillPending() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val metadata = CompletableDeferred<Unit>()
        val content = CompletableDeferred<Unit>()
        val c = WeeklyDiscoveryCoordinator(scope, { metadata.await(); JmxResult.Success(catalog()) }, { _, _, _ -> content.complete(Unit); JmxResult.Success(page("1")) }, initialCatalog = catalog())
        try {
            c.activate(); withTimeout(5_000) { content.await() }
            assertTrue(c.state.value.catalogLoading)
            withTimeout(5_000) { c.feed.state.first { it.firstLoaded } }
            assertEquals(listOf("1"), c.feed.state.value.albums.map { it.id })
            metadata.complete(Unit); idle(c)
        } finally { metadata.complete(Unit); c.close(); scope.cancel() }
    }

    @Test fun refreshIsParallelCoalescesAndNeverRepeatsWinningQueryAfterMetadata() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val metadata = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<DiscoveryQuery, Boolean>>()
        var catalogs = 0
        val c = WeeklyDiscoveryCoordinator(scope, { fresh -> catalogs++; if (fresh) metadata.await(); JmxResult.Success(catalog()) }, { q, _, fresh -> calls.add(q to fresh); JmxResult.Success(page(if (fresh) "2" else "1")) })
        try {
            c.activate(); idle(c)
            repeat(20) { c.refresh() }
            withTimeout(5_000) { c.feed.state.first { !it.loading && it.albums.firstOrNull()?.id == "2" } }
            assertTrue(c.state.value.refreshing) // Metadata is still pending, even though content finished.
            assertEquals(2, calls.size)
            metadata.complete(Unit); idle(c)
            assertFalse(c.state.value.refreshing)
            assertEquals(2, catalogs)
            assertEquals(listOf(false, true), calls.map { it.second })
        } finally { metadata.complete(Unit); c.close(); scope.cancel() }
    }

    @Test fun metadataCanFinishFirstButRefreshingWaitsForActualContent() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val content = CompletableDeferred<Unit>()
        val c = WeeklyDiscoveryCoordinator(scope, { JmxResult.Success(catalog()) }, { _, _, fresh -> if (fresh) content.await(); JmxResult.Success(page("1")) })
        try {
            c.activate(); idle(c); c.refresh()
            withTimeout(5_000) { c.state.first { !it.catalogLoading } }
            assertTrue(c.state.value.refreshing)
            content.complete(Unit); idle(c)
            assertFalse(c.state.value.refreshing)
        } finally { content.complete(Unit); c.close(); scope.cancel() }
    }

    @Test fun catalogFailureDoesNotBlockKnownQueryOrDiscardSelection() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = WeeklyDiscoveryCoordinator(scope, { JmxResult.Failure(JmxError.Network("offline")) }, { _, _, _ -> JmxResult.Success(page("1")) }, initialCatalog = catalog(), initialTypeId = "t2")
        try {
            c.activate(); idle(c)
            assertEquals(DiscoveryQuery.Weekly("w1", "t2"), c.state.value.query)
            assertNotNull(c.state.value.catalogError)
            assertEquals(listOf("1"), c.feed.state.value.albums.map { it.id })
        } finally { c.close(); scope.cancel() }
    }

    @Test fun invalidatedWeekDispatchesOnlyTheRealNewQueryAndRejectsLateOldPage() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val oldPage = CompletableDeferred<Unit>()
        val calls = mutableListOf<DiscoveryQuery>()
        val c = WeeklyDiscoveryCoordinator(scope, { fresh -> if (fresh) entered.await(); JmxResult.Success(catalog(if (fresh) "w2" else "w1")) }, { q, _, fresh ->
            calls.add(q)
            if (fresh && q == DiscoveryQuery.Weekly("w1", "t1")) { entered.complete(Unit); withContext(NonCancellable) { oldPage.await() }; JmxResult.Success(page("9")) }
            else JmxResult.Success(page(if (q == DiscoveryQuery.Weekly("w2", "t1")) "2" else "1"))
        })
        try {
            c.activate(); idle(c); c.refresh()
            withTimeout(5_000) { c.feed.state.first { it.query == DiscoveryQuery.Weekly("w2", "t1") && !it.loading } }
            assertEquals(listOf(DiscoveryQuery.Weekly("w1", "t1"), DiscoveryQuery.Weekly("w1", "t1"), DiscoveryQuery.Weekly("w2", "t1")), calls)
            oldPage.complete(Unit); yield()
            assertEquals(listOf("2"), c.feed.state.value.albums.map { it.id })
            assertEquals("w2", c.state.value.weekId)
        } finally { entered.complete(Unit); oldPage.complete(Unit); c.close(); scope.cancel() }
    }

    @Test fun selectionChangedDuringMetadataIsPreservedByWinningCatalog() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val metadata = CompletableDeferred<Unit>()
        val calls = mutableListOf<DiscoveryQuery>()
        val c = WeeklyDiscoveryCoordinator(scope, { metadata.await(); JmxResult.Success(catalog()) }, { q, _, _ -> calls.add(q); JmxResult.Success(page("1")) }, initialCatalog = catalog())
        try {
            c.activate(); yield(); c.selectType("t2")
            metadata.complete(Unit); idle(c)
            assertEquals("t2", c.state.value.typeId)
            assertEquals(listOf(DiscoveryQuery.Weekly("w1", "t1"), DiscoveryQuery.Weekly("w1", "t2")), calls)
            c.selectType("missing"); c.selectWeek("missing"); yield()
            assertEquals(2, calls.size)
        } finally { metadata.complete(Unit); c.close(); scope.cancel() }
    }

    @Test fun explicitEmptyCatalogClearsOldFeedRatherThanDisplayingStaleBooks() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = WeeklyDiscoveryCoordinator(scope, { fresh -> JmxResult.Success(if (fresh) WeekInfo(emptyList(), emptyList(), emptyMap()) else catalog()) }, { _, _, _ -> JmxResult.Success(page("1")) })
        try {
            c.activate(); idle(c); c.refresh(); idle(c)
            assertNull(c.state.value.query)
            assertTrue(c.feed.state.value.albums.isEmpty())
            assertNull(c.state.value.catalogError)
            assertFalse(c.state.value.refreshing)
        } finally { c.close(); scope.cancel() }
    }

    @Test fun failedRefreshKeepsBooksAndMetadataButDoesNotClaimCompletionSuccess() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = WeeklyDiscoveryCoordinator(scope, { fresh -> if (fresh) JmxResult.Failure(JmxError.Network("offline")) else JmxResult.Success(catalog()) }, { _, _, fresh -> if (fresh) JmxResult.Failure(JmxError.Network("offline")) else JmxResult.Success(page("1")) })
        try {
            c.activate(); idle(c); c.refresh(); idle(c)
            assertEquals(listOf("1"), c.feed.state.value.albums.map { it.id })
            assertNotNull(c.feed.state.value.error)
            assertNotNull(c.state.value.catalogError)
            assertNotNull(c.state.value.query)
            assertFalse(c.state.value.refreshing)
        } finally { c.close(); scope.cancel() }
    }

    @Test fun timeoutIsErrorNotEmptyCatalogAndClearsBusy() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = WeeklyDiscoveryCoordinator(scope, { awaitCancellation() }, { _, _, _ -> fail("Unknown catalog must not load a page"); JmxResult.Success(page("1")) }, timeoutMillis = 30)
        try {
            c.activate(); idle(c)
            assertNull(c.state.value.catalog)
            assertNotNull(c.state.value.catalogError)
            assertFalse(c.state.value.catalogLoading)
        } finally { c.close(); scope.cancel() }
    }

    @Test fun cancellationAndCloseClearBusyAndCannotRestartWork() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val c = WeeklyDiscoveryCoordinator(scope, { calls++; awaitCancellation() }, { _, _, _ -> calls++; awaitCancellation() }, initialCatalog = catalog())
        c.activate(); yield(); c.refresh(); yield(); scope.cancel()
        withTimeout(5_000) { c.state.first { !it.catalogLoading && !it.refreshing }; c.feed.state.first { !it.loading } }
        val before = calls
        c.activate(); c.refresh(); c.selectType("t2"); c.feed.refresh(); c.feed.select(DiscoveryQuery.Weekly("w1", "t2")); yield()
        assertEquals(before, calls)
    }
}
