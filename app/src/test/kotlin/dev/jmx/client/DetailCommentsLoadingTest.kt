package dev.jmx.client

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jmx.client.core.api.CommentItem
import dev.jmx.client.core.api.CommentPage
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import dev.jmx.client.core.runtime.JmxCoreConfig
import dev.jmx.client.core.runtime.publicRead
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun savePerformanceEvidence(name: String, data: Any) {
    val coordinatorRun = System.getenv("JMX_QA_RUN_ID")
    val runId = coordinatorRun ?: "standalone-" + System.nanoTime()
    check(runId.matches(Regex("^[a-zA-Z0-9_-]+$")))
    val qaRoot = System.getProperty("jmx.visual.root")?.let { File(it).parentFile }
        ?: File("build/qa-v2-search-r4")
    val directory = System.getenv("JMX_PERFORMANCE_EVIDENCE_ROOT")?.let(::File)
        ?: File(qaRoot, "performance/$runId")
    directory.mkdirs()
    check(directory.isDirectory)
    val file = File(directory, name + "-" + System.currentTimeMillis() + ".json")
    check(file.createNewFile()) { "Immutable performance evidence already exists" }
    file.writeText(GsonBuilder().serializeNulls().create().toJson(mapOf("schemaVersion" to 3, "run" to runId,
        "coordinatorBound" to (coordinatorRun != null), "evidence" to data)))
    println("PERFORMANCE_EVIDENCE " + file.absolutePath)
}

class DetailCommentsLoadingTest {
    // Invoke the ACTUAL private/internal mapper and merge functions. No algorithm copy in probes.
    private val mapper = Class.forName("dev.jmx.client.core.api.ApiMappersKt")
        .getDeclaredMethod("toCommentPage", JsonObject::class.java).apply { isAccessible = true }
    private val initialProjection = Class.forName("dev.jmx.client.AlbumDetailScreenKt")
        .getDeclaredMethod("toDetailCommentsState", CommentPage::class.java).apply { isAccessible = true }
    private val merge = Class.forName("dev.jmx.client.AlbumDetailScreenKt")
        .getDeclaredMethod("mergeCommentPages", List::class.java, List::class.java).apply { isAccessible = true }
    private fun project(root: JsonObject): CommentPage = mapper.invoke(null, root) as CommentPage
    private fun initial(page: CommentPage): DetailCommentsState = initialProjection.invoke(null, page) as DetailCommentsState
    @Suppress("UNCHECKED_CAST")
    private fun merged(existing: List<CommentItem>, incoming: List<CommentItem>): List<CommentItem> =
        merge.invoke(null, existing, incoming) as List<CommentItem>

    private inner class Fixture(val count: Int) : AutoCloseable {
        val requests = AtomicInteger()
        val maintenance = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockResponse = false
        // Construct/encrypt exactly once, BEFORE request/sampling. Every arm sees these same bytes.
        val roots: Map<Int, JsonObject> = (1..3).associateWith { page ->
            val offset = if (page == 1) 0 else count / 2
            val comments = if (page == 3) emptyList() else (offset until offset + count).map { index ->
                mapOf("CID" to "c$index", "UID" to "u$index", "username" to "fixture",
                    "comment" to "fixed-comment-$index", "likes" to index, "addtime" to "2023-11-21",
                    "replys" to listOf(mapOf("CID" to "r$index", "comment" to "reply",
                        "replys" to listOf(mapOf("CID" to "n$index", "comment" to "nested")))))
            } + if (page == 2) listOf(mapOf("CID" to "", "comment" to "blank-id"), mapOf("comment" to "null-id")) else emptyList()
            val data = linkedMapOf<String, Any>("comments" to comments,
                "raw_number" to 7, "raw_boolean" to true, "raw_object" to mapOf("x" to listOf(1, 2)))
            if (page != 3) data["total"] = count * 2 + 2
            JsonParser.parseString(Gson().toJson(data)).asJsonObject
        }
        private val bodies = roots.mapValues { (_, root) ->
            val encrypted = AesEcbPkcs7.encryptStringToBase64(root.toString(),
                JmxHash.md5Hex("1700566805" + JmxProtocolConstants.DataSecret))
            Gson().toJson(mapOf("code" to 200, "data" to encrypted))
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.host == "fixture.invalid")
            check(request.url.queryParameter("lang") == "TW")
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (request.url.encodedPath == "/setting") {
                maintenance.incrementAndGet()
                response.code(503).body("controlled maintenance unavailable".toResponseBody()).build()
            } else {
                check(request.url.encodedPath == "/forum")
                requests.incrementAndGet()
                if (blockResponse) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                val page = checkNotNull(request.url.queryParameter("page")).toInt()
                response.code(200).body(checkNotNull(bodies[page]).toResponseBody()).build()
            }
        }.build()
        val core = JmxCore.create(JmxCoreConfig(okHttpClient = client,
            apiClock = object : ApiClock { override fun nowSeconds() = 1700566805L },
            retryPolicy = DefaultRetryPolicy(maxAttempts = 1), contentLanguageProvider = { "TW" },
            domainServerUrls = emptyList()))
        val repository = AlbumDetailRepository(core, Mockito.mock(AccountRepository::class.java))
        init { core.endpointManager.replaceAll(listOf("https://fixture.invalid")) }
        suspend fun ready() { core.initializer.initialize(); core.initializer.refreshInBackground()?.await() }
        suspend fun page(page: Int): CommentPage =
            (core.publicRead { core.interactionApi.albumComments("fixture-album", page) } as JmxResult.Success).value

        // Same historical repository semantics, only omitting the run1 outer dispatcher wrapper.
        suspend fun legacyInitial(): DetailCommentsState {
            try {
                val result = withTimeoutOrNull(10_000L) {
                    core.publicRead { core.interactionApi.albumComments("fixture-album", page = 1) }
                } ?: return DetailCommentsState(error = "评论请求超时，可稍后重试。")
                return when (result) {
                    is JmxResult.Success -> initial(result.value)
                    is JmxResult.Failure -> DetailCommentsState(error = result.error.toUiMessage())
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { return DetailCommentsState(error = error.message ?: "评论加载出现未知异常。") }
        }
        suspend fun legacyMore(current: DetailCommentsState): DetailCommentsState {
            if (current.endReached) return current.copy(isLoading = false)
            try {
                return when (val result = core.publicRead { core.interactionApi.albumComments("fixture-album", page = current.nextPage) }) {
                    is JmxResult.Success -> {
                        val items = merged(current.items, result.value.comments)
                        val total = result.value.total ?: current.total
                        current.copy(items = items, total = total, nextPage = current.nextPage + 1,
                            isLoading = false, error = null,
                            endReached = result.value.comments.isEmpty() || (total != null && items.size >= total))
                    }
                    is JmxResult.Failure -> current.copy(isLoading = false, error = result.error.toUiMessage())
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { return current.copy(isLoading = false, error = error.message ?: "评论加载出现未知异常。") }
        }
        override fun close() { release.countDown(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun frozenFixturesKeepRawRepliesOrderDedupeAndPaginationEquivalent() = runBlocking {
        val rows = listOf(20, 200, 2000).map { count -> Fixture(count).use { f ->
            f.ready()
            val expected = project(f.roots.getValue(1))
            assertEquals(expected, f.page(1))
            assertEquals(expected, DetailCommentsLoader.load { f.page(1) })
            assertEquals(7, (expected.raw["raw_number"] as Number).toInt())
            assertEquals(true, expected.raw["raw_boolean"])
            val old = f.legacyInitial()
            val actual = f.repository.loadInitialComments("fixture-album")
            assertEquals(old, actual)
            val moreOld = f.legacyMore(old.copy(isLoading = true, error = "old"))
            val more = f.repository.loadMoreComments("fixture-album", actual.copy(isLoading = true, error = "old"))
            assertEquals(moreOld, more)
            assertEquals(count + count / 2 + 2, more.items.size)
            assertEquals((0 until count + count / 2).map { "c$it" }, more.items.dropLast(2).map { it.id })
            assertEquals(listOf("blank-id", "null-id"), more.items.takeLast(2).map { it.content })
            assertEquals("nested", more.items.first().replies.first().replies.first().content)
            assertEquals(3, more.nextPage); assertFalse(more.isLoading); assertNull(more.error)
            val empty = f.repository.loadMoreComments("fixture-album", more.copy(isLoading = true))
            assertEquals(f.legacyMore(more.copy(isLoading = true)), empty)
            assertTrue(empty.endReached); assertEquals(more.total, empty.total)
            val n = f.requests.get()
            assertEquals(empty, f.repository.loadMoreComments("fixture-album", empty.copy(isLoading = true)))
            assertEquals(n, f.requests.get())
            mapOf("commentCount" to count, "mergedCount" to more.items.size, "pageRawAndRepliesEqual" to true,
                "blankNullIdsPreserved" to true, "emptyPageTotalFallback" to true)
        } }
        savePerformanceEvidence("comments-equivalence", mapOf("rows" to rows, "actualMapperReflection" to true))
    }

    @Test fun boundedActualProjectionWorkersAndCancelledLateWorkDoNotPublish() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "simulated-ui") }
        executor.asCoroutineDispatcher().use { ui -> Fixture(200).use { f ->
            f.ready()
            val active = AtomicInteger(); val peak = AtomicInteger()
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val workerThreadIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
            val uiThreadId = withContext(ui) { Thread.currentThread().threadId() }
            val tasks = (0 until 8).map { async(ui) { DetailCommentsLoader.load {
                threads.add(Thread.currentThread().name)
                workerThreadIds.add(Thread.currentThread().threadId())
                val now = active.incrementAndGet(); peak.updateAndGet { maxOf(it, now) }; entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    project(f.roots.getValue(1))
                } finally { active.decrementAndGet() }
            } } }
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(2, peak.get()) }
            finally { release.countDown() }
            assertEquals(List(8) { project(f.roots.getValue(1)) }, tasks.awaitAll())
            assertTrue(workerThreadIds.isNotEmpty())
            assertFalse("Projection must leave the simulated UI thread, regardless of debug name suffix", workerThreadIds.contains(uiThreadId))
            val workEntered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            var published = false
            val late = launch(ui) { DetailCommentsLoader.load {
                workEntered.complete(Unit); withContext(NonCancellable) { finish.await() }; "late-success"
            }; published = true }
            workEntered.await(); late.cancel(); finish.complete(Unit); late.join(); assertFalse(published)
            f.blockResponse = true
            var repositoryPublished = false
            val pending = launch(ui) { f.repository.loadInitialComments("fixture-album"); repositoryPublished = true }
            assertTrue(withContext(Dispatchers.IO) { f.entered.await(5, TimeUnit.SECONDS) })
            pending.cancel(); f.release.countDown(); pending.join(); assertFalse(repositoryPublished)
            savePerformanceEvidence("comments-bounds-cancel", mapOf("peakExecutingWorkers" to peak.get(),
                "maxOutstandingLoadsIsNotClaimed" to true, "threadNames" to threads.toList(),
                "simulatedUiThreadId" to uiThreadId, "workerThreadIds" to workerThreadIds.toList(),
                "uiThreadIdentityExcluded" to !workerThreadIds.contains(uiThreadId),
                "latePublication" to published, "cancelledRepositoryPublication" to repositoryPublished,
                "actualProjectionUsed" to true, "androidFramesMeasured" to false))
        } }
    }

    private fun percentile(values: List<Long>, percent: Int): Double? {
        val sorted = values.sorted(); if (sorted.isEmpty()) return null
        return sorted[((sorted.size * percent + 99) / 100 - 1).coerceAtLeast(0)] / 1_000_000.0
    }
    private fun heartbeatPercentile(values: List<Long>, percent: Int): Double? =
        if (values.size < 20) null else percentile(values, percent)

    @Test fun emptyAndSparseMeasurementsAreNotReportedAsZeroOrReliableTails() {
        assertNull(percentile(emptyList(), 50))
        assertNull(heartbeatPercentile(emptyList(), 95))
        assertNull(heartbeatPercentile(listOf(16_000_000L), 95))
        assertEquals(16.0, percentile((1L..16L).map { it * 1_000_000L }, 95)!!, 0.0)
        assertTrue(GsonBuilder().serializeNulls().create().toJson(mapOf("p95" to percentile(emptyList(), 95))).contains("\"p95\":null"))
    }

    private suspend fun measure(ui: CoroutineDispatcher, work: suspend () -> Any): Map<String, Any?> = coroutineScope {
        repeat(4) { withContext(ui) { work() } }
        val gaps = ArrayList<Long>(); var gapCount = 0; var maximum = 0L
        val heartbeatReady = CompletableDeferred<Unit>()
        val stopRequested = AtomicBoolean()
        var windowStart = 0L; var windowEnd = 0L
        val heartbeat = launch(ui) {
            windowStart = System.nanoTime()
            var last = windowStart
            heartbeatReady.complete(Unit)
            while (isActive) {
                delay(1)
                val now = System.nanoTime(); val gap = now - last; last = now
                gapCount++; maximum = maxOf(maximum, gap)
                if (gaps.size < 2048) gaps.add(gap)
                // Include the terminal interval instead of cancelling a due heartbeat before it observes the last work.
                if (stopRequested.get()) { windowEnd = now; break }
            }
        }
        val latency = ArrayList<Long>()
        try {
            heartbeatReady.await()
            repeat(16) {
                val start = System.nanoTime(); withContext(ui) { work() }; latency.add(System.nanoTime() - start)
            }
            // Both arms use the same minimum observation window; long work may extend it and is reported explicitly.
            val remaining = 1_000_000_000L - (System.nanoTime() - windowStart)
            if (remaining > 0) delay((remaining + 999_999L) / 1_000_000L)
        } finally {
            stopRequested.set(true)
            try { withTimeout(5_000L) { heartbeat.join() } } finally { heartbeat.cancelAndJoin() }
        }
        val bean = ManagementFactory.getThreadMXBean()
        val cpuSupported = bean.isThreadCpuTimeSupported
        if (cpuSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        val id = withContext(ui) { Thread.currentThread().threadId() }
        val cpu = ArrayList<Long>()
        if (cpuSupported) repeat(8) {
            val before = bean.getThreadCpuTime(id); withContext(ui) { work() }
            val after = bean.getThreadCpuTime(id)
            if (before >= 0 && after >= before) cpu.add(after - before)
        }
        mapOf("samples" to latency.size, "loadP50Millis" to percentile(latency, 50), "loadP95Millis" to percentile(latency, 95),
            "loadRawSamplesNanos" to latency, "loadP95IsMaximumAtThisSampleCount" to (latency.size == 16),
            "simulatedUiHeartbeatP50Millis" to heartbeatPercentile(gaps, 50), "simulatedUiHeartbeatP95Millis" to heartbeatPercentile(gaps, 95),
            "simulatedUiHeartbeatMaxAllMillis" to if (gapCount == 0) null else maximum / 1_000_000.0,
            "heartbeatTotalCount" to gapCount, "heartbeatPercentileSampleCount" to gaps.size,
            "heartbeatRawFirstSamplesNanos" to gaps, "heartbeatWindowMillis" to (windowEnd - windowStart) / 1_000_000.0,
            "heartbeatTailSampleStatus" to if (gaps.size < 20) "insufficient; percentiles withheld" else "descriptive host samples only",
            "heartbeatPercentileSampling" to "first up to 2048; minimum 20 for descriptive percentile; max covers all including terminal interval; no Android frame model",
            "simulatedUiCpuTimeSupported" to cpuSupported, "cpuSamplesWithoutHeartbeat" to cpu.size,
            "cpuRawSamplesNanos" to cpu, "cpuZeroDoesNotProveZeroUiWork" to true,
            "minimumPositiveObservedCpuDeltaMillis" to cpu.filter { it > 0 }.minOrNull()?.let { it / 1_000_000.0 },
            "simulatedUiCpuP50Millis" to percentile(cpu, 50), "simulatedUiCpuP95Millis" to percentile(cpu, 95))
    }

    @Test fun controlledReadProjectionMergeAndIsolatedCpuReportWithoutFrameClaims() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "simulated-ui") }
        executor.asCoroutineDispatcher().use { ui ->
            val idleBaseline = measure(ui) { Unit }
            val rows = listOf(20, 200, 2000).flatMap { count -> Fixture(count).use { f ->
                f.ready()
                val first = initial(project(f.roots.getValue(1)))
                val incoming = project(f.roots.getValue(2))
                val current = first.copy(isLoading = true, error = "old")
                fun op(name: String, work: suspend (Boolean) -> Any) = name to work
                val operations = listOf(
                    op("initial_read_projection") { after -> if (after) f.repository.loadInitialComments("fixture-album") else f.legacyInitial() },
                    op("more_read_projection_merge") { after -> if (after) f.repository.loadMoreComments("fixture-album", current) else f.legacyMore(current) },
                    op("isolated_actual_projection_and_raw_no_parse") { after ->
                        if (after) DetailCommentsLoader.load { project(f.roots.getValue(1)) } else project(f.roots.getValue(1)) },
                    op("isolated_actual_merge_no_read_no_parse") { after ->
                        if (after) DetailCommentsLoader.load { merged(first.items, incoming.comments) } else merged(first.items, incoming.comments) }
                )
                operations.mapIndexed { index, (operation, work) ->
                    // Alternate order across sizes/operations; observations, never pass/fail speed thresholds.
                    val afterFirst = (index + count / 20) % 2 == 0
                    val arm1 = measure(ui) { work(afterFirst) }; val arm2 = measure(ui) { work(!afterFirst) }
                    mapOf("commentCount" to count, "nestedReplyDepth" to 2, "operation" to operation,
                        "order" to if (afterFirst) "after-before" else "before-after",
                        "before" to if (afterFirst) arm2 else arm1, "after" to if (afterFirst) arm1 else arm2)
                }
            } }
            savePerformanceEvidence("comments-heartbeat", mapOf("fixtureHost" to "fixture.invalid", "language" to "TW",
                "clock" to 1700566805L, "session" to "same fresh in-memory anonymous fixture per size for both arms",
                "responseCache" to "disabled; /forum unchanged", "transport" to "frozen in-process OkHttp response; no socket",
                "maintenance" to "fixed 503 /setting, empty domain servers, preinitialized before samples",
                "coreAesJsonParse" to "unchanged existing core PROCESSING_DISPATCHER(4); excluded from isolated probes",
                "boundedCommentCpu" to "DetailCommentsLoader executing CPU segments limited to 2, not total outstanding requests",
                "androidFramesMeasured" to false, "ttfbImprovementClaimed" to false,
                "hostIdleHeartbeatBaseline" to idleBaseline,
                "host" to mapOf("javaVersion" to System.getProperty("java.version"), "osName" to System.getProperty("os.name"),
                    "processors" to Runtime.getRuntime().availableProcessors()),
                "measurementLimits" to "Single host descriptive observation, not paired statistical speedup: 16 load samples (nearest-rank p95=max), 8 CPU samples; CPU clock granularity may round deltas to zero; heartbeat window minimum 1000ms with start barrier and terminal flush. No Android frames or true TTFB.",
                "timingsAreObservationsNotAssertions" to true, "rows" to rows))
        }
    }
}
