package dev.jmx.client.core.network

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NetworkSchedulingTest {
    @Test fun backgroundCannotConsumeReservedInteractiveSlots() = runBlocking {
        val gate = NetworkAdmissionGate(5, 2)
        val leases = (0..1).map { gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND)) }
        val pending = (0..2).map { async { gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND)) } }
        yield()
        assertEquals(2, gate.snapshot().active)
        val foreground = gate.acquire(NetworkPrioritySignal())
        assertEquals(3, gate.snapshot().active)
        foreground.close(); pending.forEach { it.cancelAndJoin() }; leases.forEach { it.close() }
        assertEquals(NetworkAdmissionGate.Snapshot(0, 0, 0, 0), gate.snapshot())
    }

    @Test fun queuedBackgroundCanBePromotedWithoutASecondRequest() = runBlocking {
        val gate = NetworkAdmissionGate(3, 1)
        val held = gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND))
        val signal = NetworkPrioritySignal(NetworkPriority.BACKGROUND)
        val promoted = async { gate.acquire(signal) }
        yield(); assertFalse(promoted.isCompleted)
        signal.promote()
        val lease = withTimeout(1000) { promoted.await() }
        assertEquals(2, gate.snapshot().active)
        lease.close(); held.close()
        assertEquals(0, gate.snapshot().active)
    }

    @Test fun foregroundHasPriorityButBoundedBurstsPermitBackgroundProgress() = runBlocking {
        val gate = NetworkAdmissionGate(1, 1, foregroundBurst = 2)
        val held = gate.acquire()
        val order = mutableListOf<String>()
        val background = async { gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND)).use { order.add("b") } }
        yield()
        val foreground = (1..4).map { i -> async { gate.acquire().use { order.add("f$i") } } }
        yield(); held.close(); (foreground + background).awaitAll()
        assertEquals(listOf("f1", "b", "f2", "f3", "f4"), order)
    }

    @Test fun cancellationAfterGrantBeforeDispatchReturnsPermitExactlyOnce() = runBlocking {
        val gate = NetworkAdmissionGate(1, 1)
        val held = gate.acquire()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire().use {} }
        held.close(); pending.cancelAndJoin()
        assertEquals(0, gate.snapshot().active)
        gate.acquire().use { assertEquals(1, gate.snapshot().active) }
        assertEquals(0, gate.snapshot().active)
    }

    @Test fun cancelledQueueDoesNotLeavePhantomWork() = runBlocking {
        val gate = NetworkAdmissionGate(1, 1)
        val held = gate.acquire()
        val pending = (1..20).map { async(start = CoroutineStart.UNDISPATCHED) { gate.acquire().use {} } }
        pending.forEach { it.cancelAndJoin() }; held.close()
        assertEquals(NetworkAdmissionGate.Snapshot(0, 0, 0, 0), gate.snapshot())
    }

    @Test fun boundedQueueRejectsWithoutLeakingCapacity() = runBlocking {
        val gate = NetworkAdmissionGate(1, 1, maxPending = 1)
        val held = gate.acquire()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire().use {} }
        try { gate.acquire(); fail("Expected rejection") } catch (_: java.util.concurrent.RejectedExecutionException) {}
        pending.cancelAndJoin(); held.close()
        assertEquals(0, gate.snapshot().active)
    }

    @Test fun contendedConcurrentCancellationAndReleasePreserveAccounting() = runBlocking {
        val gate = NetworkAdmissionGate(5, 2, maxPending = 2000)
        coroutineScope {
            (0 until 500).map { i -> launch(Dispatchers.Default) {
                val signal = NetworkPrioritySignal(if (i % 2 == 0) NetworkPriority.BACKGROUND else NetworkPriority.FOREGROUND)
                gate.acquire(signal).use { yield() }
            }.also { if (i % 3 == 0) it.cancel() } }.joinAll()
        }
        assertEquals(NetworkAdmissionGate.Snapshot(0, 0, 0, 0), gate.snapshot())
    }

    @Test fun cancellingOriginalSubscriberDoesNotCancelOtherSubscribers() = runBlocking {
        val owner = SupervisorJob()
        val shared = SharedReadRequests<Int>(CoroutineScope(owner + Dispatchers.Default))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async { shared.await("a") { calls.incrementAndGet(); entered.complete(Unit); release.await(); 7 } }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { shared.await("a") { error("Duplicate") } }
        first.cancelAndJoin(); release.complete(Unit)
        assertEquals(7, second.await()); assertEquals(1, calls.get()); assertEquals(0, shared.activeCount)
        owner.cancel()
    }

    @Test fun lastSubscriberCancellationCancelsUnderlyingWorkAndAllowsRetry() = runBlocking {
        val owner = SupervisorJob()
        val shared = SharedReadRequests<Int>(CoroutineScope(owner + Dispatchers.Default))
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val first = async { shared.await("a") { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } } }
        entered.await(); first.cancelAndJoin(); withTimeout(1000) { cancelled.await() }
        assertEquals(0, shared.activeCount)
        assertEquals(8, shared.await("a") { 8 })
        owner.cancel()
    }

    @Test fun cancelledProducerScopeCannotStrandADeferred() = runBlocking {
        val owner = SupervisorJob().apply { cancel() }
        val shared = SharedReadRequests<Int>(CoroutineScope(owner + Dispatchers.Default))
        try { withTimeout(1000) { shared.await("a") { 1 } }; fail("Expected cancellation") }
        catch (e: CancellationException) { assertFalse(e is TimeoutCancellationException) }
        assertEquals(0, shared.activeCount)
    }

    @Test fun foregroundJoiningSharedBackgroundWorkPromotesItsAdmission() = runBlocking {
        val owner = SupervisorJob()
        val gate = NetworkAdmissionGate(3, 1)
        val held = gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND))
        val shared = SharedReadRequests<Int>(CoroutineScope(owner + Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val background = async { backgroundNetwork { shared.await("a") { started.complete(Unit); gate.acquire().use { 9 } } } }
        started.await()
        while (gate.snapshot().backgroundWaiting == 0) yield()
        val foreground = async { shared.await("a") { error("duplicate") } }
        assertEquals(9, withTimeout(1000) { foreground.await() })
        assertEquals(9, background.await()); held.close(); owner.cancel()
    }

    @Test fun fullBackgroundQueueCannotRejectAnAvailableReservedForegroundSlot() = runBlocking {
        val gate = NetworkAdmissionGate(3, 1, maxPending = 1)
        val held = gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(NetworkPrioritySignal(NetworkPriority.BACKGROUND)).use {} }
        withTimeout(1000) { gate.acquire().use { assertEquals(2, gate.snapshot().active) } }
        assertEquals(1, gate.snapshot().backgroundWaiting)
        pending.cancelAndJoin(); held.close()
        assertEquals(NetworkAdmissionGate.Snapshot(0, 0, 0, 0), gate.snapshot())
    }

    @Test fun metricStagesAreBoundedAndPercentilesAreNearestRank() {
        val metrics = RequestMetricsRecorder(20)
        (1..100).forEach { metrics.stage("/chapter", LoadStage.ADMISSION, it * 1_000_000L) }
        val summary = metrics.stageSummaries().single()
        assertEquals(20, summary.count); assertEquals(90, summary.averageMillis); assertEquals(99, summary.p95Millis)
        metrics.clear(); assertTrue(metrics.stageSummaries().isEmpty())
    }
}
