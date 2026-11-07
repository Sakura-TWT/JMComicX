package dev.jmx.client

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AlbumUpdateConcurrencyTest {
    private class MemoryStore : AlbumUpdateObservations {
        private val lock = Any()
        private var data = emptyMap<String, AlbumUpdateRecord>()
        override fun records() = synchronized(lock) { data.toMap() }
        override fun observe(albumId: String, chapterCount: Int, at: Long) = synchronized(lock) {
            data = data + (albumId to mergeAlbumObservation(data[albumId], albumId, chapterCount, at))
        }
    }

    @Test fun scannerBoundsConcurrencyAndKeepsPartialSuccess() = runBlocking {
        val store = MemoryStore()
        val inFlight = AtomicInteger()
        val maximum = AtomicInteger()
        val scanner = AlbumUpdateScanner(store, { id ->
            val active = inFlight.incrementAndGet()
            maximum.updateAndGet { maxOf(it, active) }
            try { yield(); if (id == "2") null else 3 } finally { inFlight.decrementAndGet() }
        })
        val result = scanner.scan((1..40).map(Int::toString) + listOf(" 1 ", ""))
        assertEquals(39, result.scanned)
        assertEquals(1, result.failed)
        assertFalse(result.completed)
        assertTrue(maximum.get() <= 4)
        assertEquals(39, store.records().size)
    }

    @Test fun cancellationDoesNotRollBackCompletedObservations() = runBlocking {
        val store = MemoryStore()
        val saved = CompletableDeferred<Unit>()
        val scanner = AlbumUpdateScanner(store, { id -> if (id == "1") 3 else awaitCancellation() })
        val job = launch { scanner.scan(listOf("1", "2")) { saved.complete(Unit) } }
        withTimeout(5_000) { saved.await() }
        job.cancelAndJoin()
        assertEquals(3, store.records()["1"]?.latestChapterCount)
        assertNull(store.records()["2"])
    }

    @Test fun itemTimeoutIsReportedAsFailureWithoutBlockingWholeRound() = runBlocking {
        val store = MemoryStore()
        val scanner = AlbumUpdateScanner(store, { id -> if (id == "1") 3 else awaitCancellation() }, itemTimeoutMillis = 100)
        val result = withTimeout(5_000) { scanner.scan(listOf("1", "2")) }
        assertEquals(1, result.scanned)
        assertEquals(1, result.failed)
        assertEquals(1, store.records().size)
    }

    @Test fun oldScanCannotOverwriteDetailDuringItsSuspension() = runBlocking {
        val store = MemoryStore()
        store.observe("1", 10, 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scanner = AlbumUpdateScanner(store, { started.complete(Unit); release.await(); 11 }, observationTime = { 2L })
        val scan = async { scanner.scan(listOf("1")) }
        withTimeout(5_000) { started.await() }
        store.observe("1", 13, 3)
        release.complete(Unit)
        scan.await()
        assertEquals(13, store.records()["1"]?.latestChapterCount)
    }

    @Test fun forcedRequestsCoalesceAndFavoritesAreNotLost() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Boolean>()
        val scanning = AtomicBoolean()
        val queue = AlbumUpdateScanQueue(scope, scanning::set) { favorites: Boolean ->
            calls += favorites
            if (calls.size == 1) { started.complete(Unit); release.await() }
            calls.size
        }
        try {
            val first = async { queue.request(false) }
            started.await()
            val second = async { queue.request(false) }
            val third = async { queue.request(true) }
            yield()
            assertTrue(scanning.get())
            release.complete(Unit)
            assertEquals(1, first.await())
            assertEquals(2, second.await())
            assertEquals(2, third.await())
            assertEquals(listOf(false, true), calls)
            withTimeout(5_000) { while (scanning.get()) yield() }
        } finally { scope.cancel() }
    }

    @Test fun callerCancellationDoesNotCancelAppOwnedWorker() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val queue = AlbumUpdateScanQueue(scope, {}) { _: Boolean -> started.complete(Unit); release.await(); completed.complete(Unit); 1 }
        try {
            val caller = launch { queue.request(false) }
            started.await()
            caller.cancelAndJoin()
            release.complete(Unit)
            withTimeout(5_000) { completed.await() }
        } finally { scope.cancel() }
    }

    @Test fun cancelledOwnerReleasesPendingRequestsAndScanningState() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val scanning = AtomicBoolean()
        val queue = AlbumUpdateScanQueue<Int>(scope, scanning::set) { started.complete(Unit); awaitCancellation() }
        val first = async { runCatching { queue.request(false) } }
        started.await()
        val second = async { runCatching { queue.request(true) } }
        yield()
        scope.cancel()
        assertTrue(withTimeout(5_000) { first.await() }.exceptionOrNull() is CancellationException)
        assertTrue(withTimeout(5_000) { second.await() }.exceptionOrNull() is CancellationException)
        assertFalse(scanning.get())
    }

    @Test fun recoveryCooldownIsBoundedAndScopedToAuthenticationGeneration() {
        val guard = SessionRecoveryGuard(emptyCooldownMillis = 100, failureCooldownMillis = 50)
        guard.verifyEmpty(1, 100)
        assertTrue(guard.emptyAlreadyVerified(1, 150))
        assertFalse(guard.emptyAlreadyVerified(2, 150))
        assertFalse(guard.emptyAlreadyVerified(1, 200))
        assertFalse(guard.emptyAlreadyVerified(1, 99))
        val error = JmxResult.Failure(JmxError.Api(401, "synthetic login failure"))
        guard.failed(1, 100, error)
        assertSame(error, guard.recentFailure(1, 120))
        assertNull(guard.recentFailure(2, 120))
        assertNull(guard.recentFailure(1, 150))
        guard.succeeded()
        assertNull(guard.recentFailure(1, 120))
    }
}
