package dev.jmx.client.core.runtime

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** FIFO weighted admission: never partially acquires a large request (no multi-semaphore deadlock). */
class WeightedResourceGate(val capacity: Long) {
    init { require(capacity > 0) }
    class Lease internal constructor(private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    data class Snapshot(val used: Long, val waiting: Int)
    private class Waiter(val bytes: Long, val continuation: CancellableContinuation<Lease>) {
        var granted = false
        var closed = false
    }
    private val lock = Any()
    private val queue = ArrayDeque<Waiter>()
    private var used = 0L
    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(used, queue.size) }
    suspend fun acquire(bytes: Long): Lease {
        require(bytes in 1..capacity) { "Resource exceeds the process decode budget" }
        return suspendCancellableCoroutine { c ->
            val waiter = Waiter(bytes, c)
            c.invokeOnCancellation { close(waiter) }
            val grants = synchronized(lock) {
                if (!waiter.closed) queue.addLast(waiter)
                grantReady()
            }
            resume(grants)
        }
    }
    private fun close(waiter: Waiter) {
        val grants = synchronized(lock) {
            if (waiter.closed) return
            waiter.closed = true
            if (waiter.granted) used -= waiter.bytes else queue.remove(waiter)
            grantReady()
        }
        resume(grants)
    }
    private fun grantReady(): List<Waiter> {
        val grants = mutableListOf<Waiter>()
        while (queue.isNotEmpty()) {
            val next = queue.first()
            if (next.bytes > capacity - used) break
            queue.removeFirst()
            if (!next.closed) { used += next.bytes; next.granted = true; grants.add(next) }
        }
        return grants
    }
    private fun resume(grants: List<Waiter>) {
        grants.forEach { waiter ->
            val lease = Lease { close(waiter) }
            waiter.continuation.resume(lease) { _, value, _ -> value.close() }
        }
    }
}
