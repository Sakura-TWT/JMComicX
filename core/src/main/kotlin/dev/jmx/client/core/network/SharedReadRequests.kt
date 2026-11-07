package dev.jmx.client.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Shared READ ownership: cancelling one subscriber cannot cancel the other subscribers. */
internal class SharedReadRequests<T>(private val scope: CoroutineScope) {
    private val lock = Any()
    private val flights = mutableMapOf<String, Flight>()
    val joinedCount = AtomicInteger()
    val activeCount: Int get() = synchronized(lock) { flights.size }
    private inner class Flight(val priority: NetworkPrioritySignal) {
        var users = 0
        val result = CompletableDeferred<T>()
        lateinit var job: Job
    }

    suspend fun await(key: String, block: suspend () -> T): T {
        val priority = currentNetworkSignal().priority
        var created = false
        val flight = synchronized(lock) {
            (flights[key] ?: Flight(NetworkPrioritySignal(priority)).also { fresh ->
                created = true
                fresh.job = scope.launch(NetworkWorkContext(fresh.priority), start = CoroutineStart.LAZY) {
                    try {
                        val value = block()
                        synchronized(lock) { if (flights[key] === fresh) flights.remove(key) }
                        fresh.result.complete(value)
                    } catch (cancelled: CancellationException) {
                        fresh.result.cancel(cancelled)
                    } catch (failure: Throwable) {
                        fresh.result.completeExceptionally(failure)
                    } finally {
                        synchronized(lock) { if (flights[key] === fresh) flights.remove(key) }
                    }
                }
                flights[key] = fresh
            }).also { it.users++ }
        }
        if (created) flight.job.invokeOnCompletion { cause ->
            if (cause != null) {
                synchronized(lock) { if (flights[key] === flight) flights.remove(key) }
                flight.result.completeExceptionally(cause)
            }
        }
        if (!created) joinedCount.incrementAndGet()
        if (priority == NetworkPriority.FOREGROUND) flight.priority.promote()
        // LAZY.start, await and cancellation run outside the shared-request lock.
        flight.job.start()
        try {
            return flight.result.await()
        } finally {
            val cancel = synchronized(lock) {
                flight.users--
                if (flight.users == 0 && !flight.result.isCompleted) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.job
                } else null
            }
            cancel?.cancel()
        }
    }
}
