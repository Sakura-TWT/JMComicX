package dev.jmx.client.core.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WeightedResourceGateTest {
    @Test fun weightedRequestsAreAtomicAndFifo() = runBlocking {
        val gate = WeightedResourceGate(10)
        val held = gate.acquire(6)
        val order = mutableListOf<Int>()
        val big = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(7).use { order.add(7) } }
        val small = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(3).use { order.add(3) } }
        assertEquals(WeightedResourceGate.Snapshot(6, 2), gate.snapshot())
        held.close(); big.await(); small.await(); assertEquals(listOf(7, 3), order)
        assertEquals(WeightedResourceGate.Snapshot(0, 0), gate.snapshot())
    }
    @Test fun cancelledHeadUnblocksSmallerRequestAndReleasesExactlyOnce() = runBlocking {
        val gate = WeightedResourceGate(10); val held = gate.acquire(6)
        val big = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(7).use {} }
        val small = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(3) }
        big.cancelAndJoin(); val lease = small.await(); assertEquals(9, gate.snapshot().used)
        lease.close(); lease.close(); held.close(); assertEquals(0, gate.snapshot().used)
    }
    @Test fun cancelAfterGrantBeforeDispatchCannotLeakWeightedCapacity() = runBlocking {
        val gate = WeightedResourceGate(10); val held = gate.acquire(10)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire(10).use {} }
        held.close(); waiting.cancelAndJoin(); assertEquals(0, gate.snapshot().used)
    }
    @Test fun oversizedRequestFailsBeforeEnqueue() = runBlocking {
        val gate = WeightedResourceGate(10)
        try { gate.acquire(11); fail("Must reject") } catch (_: IllegalArgumentException) {}
        assertEquals(WeightedResourceGate.Snapshot(0, 0), gate.snapshot())
    }
    @Test fun concurrentWeightedCancellationNeverExceedsBudgetOrLeavesWaiters() = runBlocking {
        val gate = WeightedResourceGate(100)
        coroutineScope { (1..300).map { i -> launch(Dispatchers.Default) {
            gate.acquire((i % 70 + 1).toLong()).use { assertTrue(gate.snapshot().used <= 100); yield() }
        }.also { if (i % 3 == 0) it.cancel() } }.joinAll() }
        assertEquals(WeightedResourceGate.Snapshot(0, 0), gate.snapshot())
    }
}
