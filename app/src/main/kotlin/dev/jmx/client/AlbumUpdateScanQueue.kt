package dev.jmx.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** One app-owned worker, one coalesced follow-up; caller cancellation does not drop queued refreshes. */
internal class AlbumUpdateScanQueue<T>(
    private val scope: CoroutineScope,
    private val scanningChanged: (Boolean) -> Unit,
    private val runRound: suspend (includeFavorites: Boolean) -> T,
) {
    private data class Pending<T>(var includeFavorites: Boolean, val waiters: MutableList<CompletableDeferred<T>>)
    private val lock = Any()
    private var pending: Pending<T>? = null
    private var worker: Job? = null

    suspend fun request(includeFavorites: Boolean): T {
        val reply = CompletableDeferred<T>()
        var start: Job? = null
        synchronized(lock) {
            val queued = pending
            if (queued == null) pending = Pending(includeFavorites, mutableListOf(reply))
            else { queued.includeFavorites = queued.includeFavorites || includeFavorites; queued.waiters += reply }
            if (worker == null) {
                val job = scope.launch(start = CoroutineStart.LAZY) { drain() }
                worker = job
                scanningChanged(true)
                // Also handles a parent scope cancelled before this lazy worker ever starts.
                job.invokeOnCompletion { cause ->
                    synchronized(lock) {
                        if (worker === job) {
                            pending?.waiters?.forEach { it.cancel(CancellationException("Scan worker stopped", cause)) }
                            pending = null
                            worker = null
                            scanningChanged(false)
                        }
                    }
                }
                start = job
            }
        }
        start?.start()
        return reply.await()
    }

    private suspend fun drain() {
        val owner = currentCoroutineContext()[Job]
        var active: Pending<T>? = null
        try {
            while (true) {
                val batch = synchronized(lock) {
                    pending.also {
                        pending = null
                        if (it == null && worker === owner) {
                            worker = null
                            scanningChanged(false)
                        }
                    }
                } ?: return
                active = batch
                try {
                    val result = runRound(batch.includeFavorites)
                    batch.waiters.forEach { it.complete(result) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    batch.waiters.forEach { it.completeExceptionally(error) }
                }
                active = null
            }
        } finally {
            active?.waiters?.forEach { it.cancel() }
        }
    }
}
