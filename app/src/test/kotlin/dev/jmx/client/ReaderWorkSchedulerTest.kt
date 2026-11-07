package dev.jmx.client

import dev.jmx.client.core.api.WeekType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReaderWorkSchedulerTest {
    @Test fun changingWindowCancelsOldWorkAndLateSuccessCannotPoisonNewWindow() = runBlocking {
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        val started = mutableListOf<String>(); val release = CompletableDeferred<Unit>()
        val scheduler = ReaderWorkScheduler<String>(scope) { key -> started.add(key); withContext(NonCancellable) { release.await() }; true }
        scheduler.update(listOf("old")); scheduler.update(listOf("new")); release.complete(Unit); yield()
        assertEquals(listOf("old", "new"), started); assertEquals(1, scheduler.successCount); assertEquals(0, scheduler.activeCount)
        scheduler.update(listOf("old")); yield(); assertEquals(3, started.size)
        scheduler.clear(true); owner.cancel()
    }
    @Test fun identicalWindowAndDuplicateKeysNeverStartDuplicateJobs() = runBlocking {
        val owner = SupervisorJob(); val release = CompletableDeferred<Unit>(); var calls = 0
        val scheduler = ReaderWorkScheduler<Int>(CoroutineScope(owner + Dispatchers.Unconfined)) { calls++; release.await(); true }
        scheduler.update(listOf(1, 1, 2)); scheduler.update(listOf(1, 2)); assertEquals(2, calls)
        release.complete(Unit); yield(); scheduler.update(listOf(1, 2)); assertEquals(2, calls)
        scheduler.clear(true); owner.cancel()
    }
    @Test fun failureRetriesAndSuccessfulSetRemainsWindowBounded() = runBlocking {
        var success = false; var calls = 0
        val scheduler = ReaderWorkScheduler<Int>(this) { calls++; success }
        scheduler.update(listOf(1)); yield(); scheduler.update(listOf(1)); yield(); assertEquals(2, calls)
        success = true; scheduler.update(listOf(1)); yield(); assertEquals(1, scheduler.successCount)
        repeat(100) { scheduler.update(listOf(it)); yield() }; assertEquals(1, scheduler.successCount)
        scheduler.clear(true)
    }
    @Test fun scopeAlreadyCancelledAndClosedSchedulerCannotStartOrRetainWork() = runBlocking {
        val owner = SupervisorJob().apply { cancel() }; var calls = 0
        val scheduler = ReaderWorkScheduler<Int>(CoroutineScope(owner + Dispatchers.Default)) { calls++; true }
        scheduler.update(listOf(1)); assertEquals(0, scheduler.activeCount); assertEquals(0, calls)
        scheduler.clear(true); scheduler.update(listOf(2)); assertEquals(0, scheduler.activeCount)
    }
    @Test fun resourceVersionWidthAndChapterKeysAreIndependent() = runBlocking {
        var calls = 0; val scheduler = ReaderWorkScheduler<String>(this) { calls++; true }
        for (key in listOf("c1:p1:v1:w400", "c1:p1:v1:w800", "c1:p1:v2:w800", "c2:p1:v2:w800")) { scheduler.update(listOf(key)); yield() }
        assertEquals(4, calls); scheduler.clear(true)
    }
    @Test fun clearCancelsWorkAndRemainsReusableUntilClose() = runBlocking {
        val scheduler = ReaderWorkScheduler<Int>(this) { awaitCancellation() }
        scheduler.update(listOf(1)); yield(); scheduler.clear(); yield(); assertEquals(0, scheduler.activeCount)
        scheduler.update(listOf(2)); assertEquals(1, scheduler.activeCount); scheduler.clear(true); yield()
    }
    @Test fun actualWeeklyFallbacksDoNotAlterIdsOrderOrValidServerNames() {
        val types = listOf(WeekType("manga", "日漫"), WeekType("another", "其他"), WeekType("hanman", ""))
        assertEquals(listOf("日漫", "其他", "韩漫"), types.map { it.displayTitle() })
        assertEquals(listOf("manga", "another", "hanman"), types.map { it.id })
        assertEquals("韩语作品", WeekType("hanman", "韩语作品").displayTitle())
        assertEquals("韩漫", WeekType("hanman", "HANMAN").displayTitle())
        assertEquals("类型 future", WeekType("future", null).displayTitle())
    }
}
