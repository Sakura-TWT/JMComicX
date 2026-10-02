package dev.jmx.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A rolling, bounded reorder buffer, not one coroutine (or bitmap) per page.
 * A failed later page does not cancel earlier pages: publish the valid prefix first.
 * prepare owns durable staging; cleanup only removes incomplete temporary files, after children join.
 */
internal class OfflinePagePipeline(private val capacity: Int = 4) {
    init { require(capacity in 1..8) }

    suspend fun <T> run(
        startIndex: Int,
        pageCount: Int,
        prepare: suspend (Int) -> T,
        publish: suspend (List<T>) -> Unit,
        cleanup: (Int) -> Unit,
    ) {
        require(startIndex in 0..pageCount)
        val owned = mutableSetOf<Int>()
        try {
            coroutineScope {
                val pending = ArrayDeque<Pair<Int, Deferred<Result<T>>>>()
                var next = startIndex
                fun fill() {
                    while (pending.size < capacity && next < pageCount) {
                        val index = next++
                        owned += index
                        pending.addLast(index to async {
                            try { Result.success(prepare(index)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { Result.failure(error) }
                        })
                    }
                }
                try {
                    fill()
                    while (pending.isNotEmpty()) {
                        val (index, task) = pending.removeFirst()
                        val page = task.await().getOrElse { throw OfflinePageFailure(index, it) }
                        val batch = mutableListOf(index to page)
                        // Coalesce only already-ready contiguous successes; never wait for a slow peer.
                        while (pending.firstOrNull()?.second?.isCompleted == true) {
                            val (readyIndex, readyTask) = pending.first()
                            val ready = readyTask.await()
                            if (ready.isFailure) break // Commit this prefix before reporting the failure.
                            pending.removeFirst()
                            batch += readyIndex to ready.getOrThrow()
                        }
                        currentCoroutineContext().ensureActive()
                        try { publish(batch.map { it.second }) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { throw OfflinePageFailure(index, error) }
                        batch.forEach { (committed, _) -> cleanup(committed); owned -= committed }
                        // Refill immediately after each durable publication, without a batch barrier.
                        fill()
                    }
                } finally {
                    pending.forEach { (_, task) -> task.cancel() }
                }
            }
        } finally {
            // coroutineScope has joined even cancelled children; no worker can recreate these files.
            owned.forEach(cleanup)
        }
    }
}

internal class OfflinePageFailure(val pageIndex: Int, cause: Throwable) :
    Exception("离线页面处理失败", cause)
