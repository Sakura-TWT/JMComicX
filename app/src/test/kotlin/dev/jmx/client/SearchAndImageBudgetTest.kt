package dev.jmx.client

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchAndImageBudgetTest {
    @Test fun retiredSameQueryPaginationIsCancelledAndCannotReplaceNewGeneration() = runBlocking {
        val epoch = SearchRequestEpoch(); val first = epoch.advance()
        val old = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        assertTrue(epoch.register(first, old)); old.start()
        val second = epoch.advance(); assertTrue(old.isCancelled); assertFalse(epoch.accepts(first)); assertTrue(epoch.accepts(second))
        val stale = launch(start = CoroutineStart.LAZY) { error("must not run") }
        assertFalse(epoch.register(first, stale)); assertTrue(stale.isCancelled)
        val current = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        assertTrue(epoch.register(second, current)); epoch.finished(old); epoch.advance(); assertTrue(current.isCancelled)
        old.join(); stale.join(); current.join()
    }
    @Test fun historyMatchingIsLocalStableCaseInsensitiveAndWhitespaceAware() {
        val history = listOf("韩漫", "Manga one", "JM123")
        assertSame(history, matchingSearchHistory(history, "  "))
        assertEquals(listOf("Manga one"), matchingSearchHistory(history, " mAnGa "))
        assertEquals(emptyList<String>(), matchingSearchHistory(history, "missing"))
        assertEquals(listOf("韩漫"), matchingSearchHistory(history, "漫"))
    }
    private fun album(id: String) = HomeAlbum(id, id, "", "https://img.test/$id", "https://img.test")
    @Test fun searchMergeIsStableUniqueAndStopsAtItsExplicitBudget() {
        val result = mergeSearchAlbums(listOf(album("1"), album("2")), listOf(album("2"), album("3"), album("4")), false, 3)
        assertEquals(listOf("1", "2", "3"), result.albums.map { it.id }); assertTrue(result.endReached); assertTrue(result.limitReached)
        val repeat = mergeSearchAlbums(result.albums, listOf(album("1")), false, 10)
        assertTrue(repeat.endReached); assertFalse(repeat.limitReached)
    }
    @Test fun decodeEstimateAccountsForTwoWholeBitmapsAndEncodedScratch() {
        assertEquals(1080L * 1500 * 8 + 1024 + 8L * 1024 * 1024, readerDecodeReservation(1080, 1500, 1, 1024))
        assertEquals(540L * 750 * 8 + 1024 + 8L * 1024 * 1024, readerDecodeReservation(1080, 1500, 2, 1024))
        assertTrue(readerDecodeReservation(1081, 1501, 2, 1024) > readerDecodeReservation(1080, 1500, 2, 1024))
    }
    @Test fun malformedDimensionsCannotOverflowIntoTinyDecodeReservation() {
        for ((width, height) in listOf(0 to 100, -1 to 100, Int.MAX_VALUE to Int.MAX_VALUE)) {
            try { readerDecodeReservation(width, height, 1, 1); fail("must reject") } catch (_: IllegalArgumentException) {}
        }
    }
}
