package dev.jmx.client

import dev.jmx.client.core.image.ImagePipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Test

class ReaderChapterWarmupTest {
    private data class Key(val chapter: String, val resources: List<String>, val width: Int, val session: String)

    // This is a controlled MODEL, not actual ComicReaderRepository/Coil execution.
    // It represents both prepare calls and first-two sequential execute calls, but cannot prove ROI.
    private class Work(private val blocked: Boolean) {
        val release = CompletableDeferred<Unit>()
        private val chapters = mutableSetOf<String>()
        val memory = mutableSetOf<String>()
        val disk = mutableSetOf<String>()
        var prepares = 0; var metadataHits = 0; var metadataNetwork = 0
        var execute = 0; var networkAttempts = 0; var networkCommitted = 0
        var decode = 0; var memoryHits = 0; var diskHits = 0; var cancelled = 0
        var active = 0; var peak = 0
        private fun prepare(key: Key) {
            prepares++
            if (!chapters.add(key.chapter)) metadataHits++ else metadataNetwork++
        }
        private fun encodedKey(resource: String): String = ImagePipeline().plan(resource, 100, 0).cacheKey
        private fun decodedKey(resource: String, width: Int): String {
            val plan = ImagePipeline().plan(resource, 100, 0)
            return "${plan.cacheKey}:reader:${plan.segmentCount}:decoded-v4:$width:0"
        }
        private suspend fun request(resource: String, key: Key, speculative: Boolean) {
            execute++
            val decodedKey = decodedKey(resource, key.width)
            if (decodedKey in memory) { memoryHits++; return }
            if (speculative) { active++; peak = maxOf(peak, active) }
            try {
                val encodedKey = encodedKey(resource)
                if (encodedKey !in disk) {
                    networkAttempts++
                    if (blocked) release.await()
                    disk.add(encodedKey); networkCommitted++
                } else diskHits++
                decode++; memory.add(decodedKey)
            } catch (error: CancellationException) { cancelled++; throw error }
            finally { if (speculative) active-- }
        }
        suspend fun chapter(key: Key): Boolean {
            // Existing effect prepares metadata, then warmUpChapter prepares again (usually cache hit).
            prepare(key); prepare(key)
            for (resource in key.resources.take(2)) ImageWorkBudgets.prefetch.withPermit { request(resource, key, true) }
            return true
        }
        suspend fun visible(key: Key) {
            prepare(key)
            for (resource in key.resources.take(2)) request(resource, key, false)
        }
        fun counters() = mapOf("prepareCalls" to prepares, "metadataCacheHits" to metadataHits,
            "modeledMetadataApiAttempts" to metadataNetwork, "modeledImageExecute" to execute,
            "modeledImageNetworkAttempts" to networkAttempts, "modeledImageNetworkCommitted" to networkCommitted,
            "modeledDecode" to decode, "modeledMemoryHits" to memoryHits, "modeledDiskHits" to diskHits,
            "cancelledImageRequests" to cancelled, "peakSpeculativeSlots" to peak)
    }

    private suspend fun scenario(stable: Boolean, mode: String): Map<String, Any> = coroutineScope {
        val work = Work(mode == "cold-blocked")
        val key = Key("next", listOf("https://fixture.invalid/next/00001.jpg", "https://fixture.invalid/next/00002.jpg"), 393, "anonymous-TW-v1")
        if (mode == "hit") work.chapter(key)
        val starting = work.counters()
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        val scheduler = ReaderWorkScheduler<Key>(scope) { work.chapter(it) }
        var old: Job? = null
        repeat(3) { step ->
            if (step == 1 && mode == "evicted") work.memory.clear()
            if (stable) scheduler.update(listOf(key)) else {
                old?.cancelAndJoin(); old = scope.launch { work.chapter(key) }
            }
            yield()
        }
        work.release.complete(Unit); old?.join(); yield()
        val preVisible = work.counters()
        work.visible(key)
        val total = work.counters()
        scheduler.clear(true); owner.cancelAndJoin()
        assertTrue(work.peak <= 2)
        mapOf("startingCounters" to starting, "afterWarmup" to preVisible, "includingVisibleRead" to total)
    }

    @Test fun controlledWarmupModelSeparatesAttemptsCommitsDecodesAndVisibleCost() = runBlocking {
        val rows = listOf("cold-blocked", "hit", "evicted").map { mode ->
            val old = scenario(false, mode); val stable = scenario(true, mode)
            @Suppress("UNCHECKED_CAST")
            val oldTotal = old["includingVisibleRead"] as Map<String, Int>
            @Suppress("UNCHECKED_CAST")
            val newTotal = stable["includingVisibleRead"] as Map<String, Int>
            // These are deterministic MODEL invariants, not real network/Coil performance assertions.
            assertEquals(1, oldTotal["modeledMetadataApiAttempts"])
            assertEquals(1, newTotal["modeledMetadataApiAttempts"])
            if (mode == "cold-blocked") {
                assertEquals(4, oldTotal["modeledImageNetworkAttempts"])
                assertEquals(2, newTotal["modeledImageNetworkAttempts"])
                assertEquals(2, oldTotal["cancelledImageRequests"])
                assertEquals(0, newTotal["cancelledImageRequests"])
            }
            assertEquals(oldTotal["modeledImageNetworkCommitted"], newTotal["modeledImageNetworkCommitted"])
            assertEquals(oldTotal["modeledDecode"], newTotal["modeledDecode"])
            if (mode == "evicted") {
                assertEquals(4, newTotal["modeledDecode"])
                assertEquals(2, newTotal["modeledDiskHits"])
            }
            mapOf("cacheState" to mode, "beforeCancellationRestartModel" to old, "stableDesiredModel" to stable)
        }
        savePerformanceEvidence("reader-warmup-roi", mapOf("fixture" to "two fixed pages; tail-2 -> tail-1 -> tail; then visible read",
            "executor" to "controlled executor MODEL, not actual ComicReaderRepository or Coil",
            "strictSourceEquivalence" to false, "meaningfulConsumerRoiProven" to false,
            "readerGate" to "NO_GO: no source rewrite or unused controller; model alone cannot justify Before request",
            "sourcePointers" to listOf("ComicReaderScreen.kt:476-487", "ComicReaderScreen.kt:1735-1890", "ComicReaderScreen.kt:1907-1935"),
            "representedContracts" to listOf("two prepare calls before warmup", "first two pages sequential", "shared prefetch permits=2",
                "actual ImagePipeline MD5/segment plan", "decoded-v4 width/retry0 key shape", "disk survives memory eviction", "visible consumer cost included"),
            "unrepresentedContracts" to listOf("real chapter API/public recovery/cache3 eviction", "Coil cache admission/keys/SuccessResult",
                "in-flight dedup/decoder restore/allocation", "real transport cancellation", "actual Compose effect lifecycle/paging"),
            "encodedDiskCacheRetainedOnMemoryEviction" to true, "width" to 393,
            "protocolLanguageSession" to "same anonymous-TW-v1 per arm; session is desired identity only, not invented disk-key prefix",
            "networkRequestsActuallySent" to 0, "actualBitmapDecodes" to 0, "androidFramesMeasured" to false, "rows" to rows))
    }

    @Test fun desiredIdentityExitFailureAndLateSuccessAreBounded() = runBlocking {
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>(); val started = mutableListOf<Key>()
        val old = Key("a", listOf("p1", "p2"), 393, "session1")
        val fresh = old.copy(session = "session2")
        val scheduler = ReaderWorkScheduler<Key>(scope) { key ->
            started.add(key); withContext(NonCancellable) { release.await() }; true
        }
        scheduler.update(listOf(old)); scheduler.update(listOf(old)); assertEquals(1, started.size)
        scheduler.update(emptyList()); scheduler.update(listOf(fresh)); release.complete(Unit); yield()
        assertEquals(1, scheduler.successCount)
        for (key in listOf(fresh.copy(width = 786), fresh.copy(resources = listOf("v2/p1", "v2/p2")), fresh.copy(chapter = "b"))) {
            scheduler.update(listOf(key)); yield()
        }
        assertEquals(5, started.size)
        scheduler.clear(true); owner.cancelAndJoin()
        var success = false; var attempts = 0
        val retry = ReaderWorkScheduler<Int>(this) { attempts++; success }
        retry.update(listOf(1)); yield(); retry.update(listOf(1)); yield()
        assertEquals(2, attempts); success = true; retry.update(listOf(1)); yield()
        assertEquals(1, retry.successCount); retry.clear(true)
    }
}
