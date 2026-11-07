package dev.jmx.client.core.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Scheduling metadata only; never a wire header, URL parameter, credential or cache-key part. */
enum class NetworkPriority { FOREGROUND, BACKGROUND }

class NetworkPrioritySignal(initial: NetworkPriority = NetworkPriority.FOREGROUND) {
    private val lock = Any()
    @Volatile var priority: NetworkPriority = initial
        private set
    private val listeners = mutableSetOf<() -> Unit>()

    fun promote() {
        val callbacks = synchronized(lock) {
            if (priority == NetworkPriority.FOREGROUND) return
            priority = NetworkPriority.FOREGROUND
            listeners.toList()
        }
        callbacks.forEach { it() }
    }

    internal fun subscribe(callback: () -> Unit): Closeable {
        synchronized(lock) { listeners.add(callback) }
        return Closeable { synchronized(lock) { listeners.remove(callback) } }
    }
}

class NetworkWorkContext(val signal: NetworkPrioritySignal) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NetworkWorkContext>
}

suspend fun <T> backgroundNetwork(block: suspend () -> T): T =
    withContext(NetworkWorkContext(NetworkPrioritySignal(NetworkPriority.BACKGROUND))) { block() }

internal suspend fun currentNetworkSignal(): NetworkPrioritySignal =
    currentCoroutineContext()[NetworkWorkContext]?.signal ?: NetworkPrioritySignal()

/**
 * Admission BEFORE OkHttp enqueue. Background work cannot occupy all interactive capacity.
 * Same-priority FIFO, bounded foreground bursts, live promotion and cancellation-safe permits.
 * No continuation resume, callback or job cancellation is executed while holding the state lock.
 */
class NetworkAdmissionGate(
    private val capacity: Int = 5,
    private val backgroundCapacity: Int = 2,
    private val foregroundBurst: Int = 8,
    private val maxPending: Int = 256,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init {
        require(capacity > 0 && backgroundCapacity in 1..capacity && foregroundBurst > 0 && maxPending > 0)
    }
    data class Snapshot(val active: Int, val backgroundActive: Int, val foregroundWaiting: Int, val backgroundWaiting: Int)
    private val lock = Any()
    private val waiting = ArrayList<Waiter>()
    private var active = 0
    private var backgroundActive = 0
    private var burst = 0

    private inner class Waiter(
        val signal: NetworkPrioritySignal,
        val continuation: kotlinx.coroutines.CancellableContinuation<Lease>,
        val queuedAt: Long,
    ) {
        var subscription: Closeable? = null
        var lease: Lease? = null
    }

    inner class Lease internal constructor(private val releaseAction: () -> Unit, val queueNanos: Long) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) releaseAction() }
    }

    suspend fun acquire(signal: NetworkPrioritySignal? = null): Lease {
        val effectiveSignal = signal ?: currentNetworkSignal()
        return suspendCancellableCoroutine { c ->
            val waiter = Waiter(effectiveSignal, c, nowNanos())
            waiter.subscription = effectiveSignal.subscribe { deliver(synchronized(lock) { drain() }) }
            c.invokeOnCancellation { cancel(waiter) }
            var rejected = false
            val granted = synchronized(lock) {
                val canUseReservedSlot = active < capacity && effectiveSignal.priority == NetworkPriority.FOREGROUND
                when {
                    !c.isActive -> emptyList()
                    waiting.size >= maxPending && !canUseReservedSlot -> { rejected = true; emptyList() }
                    else -> { waiting.add(waiter); drain() }
                }
            }
            if (rejected) {
                waiter.subscription?.close()
                c.resumeWith(Result.failure(RejectedExecutionException("Network admission queue is full")))
            } else if (!c.isActive && waiter.lease == null) {
                waiter.subscription?.close()
            }
            deliver(granted)
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(active, backgroundActive, waiting.count { it.signal.priority == NetworkPriority.FOREGROUND }, waiting.count { it.signal.priority == NetworkPriority.BACKGROUND })
    }

    private fun drain(): List<Waiter> {
        val grants = mutableListOf<Waiter>()
        while (active < capacity) {
            val foreground = waiting.indexOfFirst { it.signal.priority == NetworkPriority.FOREGROUND }
            val background = if (backgroundActive < backgroundCapacity) waiting.indexOfFirst { it.signal.priority == NetworkPriority.BACKGROUND } else -1
            val index = when {
                background >= 0 && (foreground < 0 || burst >= foregroundBurst) -> background
                foreground >= 0 -> foreground
                else -> break
            }
            val waiter = waiting.removeAt(index)
            val isBackground = waiter.signal.priority == NetworkPriority.BACKGROUND
            active++
            if (isBackground) { backgroundActive++; burst = 0 } else burst = (burst + 1).coerceAtMost(foregroundBurst)
            waiter.lease = Lease(
                releaseAction = { release(waiter, isBackground) },
                queueNanos = (nowNanos() - waiter.queuedAt).coerceAtLeast(0),
            )
            grants.add(waiter)
        }
        return grants
    }

    private fun deliver(grants: List<Waiter>) {
        grants.forEach { waiter ->
            waiter.continuation.resume(waiter.lease!!) { _, lease, _ -> lease.close() }
        }
    }

    private fun release(waiter: Waiter, wasBackground: Boolean) {
        waiter.subscription?.close()
        val grants = synchronized(lock) {
            active--
            if (wasBackground) backgroundActive--
            drain()
        }
        deliver(grants)
    }

    private fun cancel(waiter: Waiter) {
        waiter.subscription?.close()
        val (lease, grants) = synchronized(lock) {
            val assigned = waiter.lease
            if (assigned == null) waiting.remove(waiter)
            assigned to if (assigned == null) drain() else emptyList()
        }
        lease?.close()
        deliver(grants)
    }
}
