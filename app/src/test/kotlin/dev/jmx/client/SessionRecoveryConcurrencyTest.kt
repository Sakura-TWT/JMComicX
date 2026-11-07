package dev.jmx.client

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SessionRecoveryConcurrencyTest {
    @Test fun concurrentReadersShareOneLoginAndRetryOutsideTheMutex() = runBlocking {
        val generation = AtomicLong()
        val logins = AtomicInteger()
        val firstReaders = AtomicInteger()
        val retryReaders = AtomicInteger()
        val firstBarrier = CompletableDeferred<Unit>()
        val retryBarrier = CompletableDeferred<Unit>()
        val mutex = Mutex()
        val guard = SessionRecoveryGuard()
        val replies = (1..50).map {
            async {
                var calls = 0
                boundedSessionRequest(generation::get, mutex, guard, {
                    logins.incrementAndGet(); generation.incrementAndGet(); RecoveryLogin.Recovered
                }) {
                    if (++calls == 1) {
                        if (firstReaders.incrementAndGet() == 50) firstBarrier.complete(Unit)
                        firstBarrier.await()
                        JmxResult.Failure(JmxError.Api(401, "synthetic expired session"))
                    } else {
                        if (retryReaders.incrementAndGet() == 50) retryBarrier.complete(Unit)
                        retryBarrier.await()
                        JmxResult.Success(7)
                    }
                }
            }
        }
        val results = withTimeout(10_000) { replies.awaitAll() }
        assertTrue(results.all { it is JmxResult.Success })
        assertEquals(1, logins.get())
        assertEquals(50, retryReaders.get())
    }

    @Test fun loginFailureDoesNotCauseAConcurrentReloginStorm() = runBlocking {
        val logins = AtomicInteger()
        val generation = AtomicLong()
        val mutex = Mutex()
        val guard = SessionRecoveryGuard()
        val error = JmxResult.Failure(JmxError.Api(401, "synthetic rejected login"))
        val results = (1..50).map { async {
            boundedSessionRequest(generation::get, mutex, guard, { logins.incrementAndGet(); RecoveryLogin.Failed(error) }, clock = { 1_000L }) { error }
        } }.awaitAll()
        assertTrue(results.all { it == error })
        assertEquals(1, logins.get())
        boundedSessionRequest(generation::get, mutex, guard, { logins.incrementAndGet(); RecoveryLogin.Failed(error) }, clock = { 31_001L }) { error }
        assertEquals(2, logins.get())
    }

    @Test fun persistentEmptyDataIsNotSuccessAndCanRecoverAfterCooldown() = runBlocking {
        val generation = AtomicLong()
        val logins = AtomicInteger()
        val mutex = Mutex()
        val guard = SessionRecoveryGuard(emptyCooldownMillis = 100)
        var now = 100L
        suspend fun request() = boundedSessionRequest(generation::get, mutex, guard, {
            logins.incrementAndGet(); generation.incrementAndGet(); RecoveryLogin.Recovered
        }, treatEmptyDataAsSessionLoss = true, clock = { now }) { JmxResult.Failure(JmxError.EmptyData("synthetic empty")) }
        assertTrue(request() is JmxResult.Failure)
        assertEquals(1, logins.get())
        assertTrue(request() is JmxResult.Failure)
        assertEquals(1, logins.get())
        now = 201
        assertTrue(request() is JmxResult.Failure)
        assertEquals(2, logins.get())
    }

    @Test fun oneWorkflowRecoveryBudgetAndUnrelatedFailuresAreHonored() = runBlocking {
        var logins = 0
        var calls = 0
        val error = JmxResult.Failure(JmxError.Api(401, "synthetic expired"))
        val blocked = boundedSessionRequest({ 0 }, Mutex(), SessionRecoveryGuard(), { logins++; RecoveryLogin.Recovered }, allowRecovery = { false }) { calls++; error }
        assertEquals(error, blocked)
        assertEquals(0, logins)
        assertEquals(1, calls)
        val unrelated = JmxResult.Failure(JmxError.Schema("synthetic shape"))
        assertEquals(unrelated, boundedSessionRequest({ 0 }, Mutex(), SessionRecoveryGuard(), { logins++; RecoveryLogin.Recovered }) { unrelated })
        assertEquals(0, logins)
    }
}
