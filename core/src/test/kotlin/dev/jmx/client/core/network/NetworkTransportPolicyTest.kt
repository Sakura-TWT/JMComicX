package dev.jmx.client.core.network

import dev.jmx.client.core.cache.JsonResponseCache
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.*
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NetworkTransportPolicyTest {
    private fun response(json: String = """{"ok":true}"""): MockResponse {
        val cipher = AesEcbPkcs7.encryptStringToBase64(json, JmxHash.md5Hex("1${JmxProtocolConstants.DataSecret}"))
        return MockResponse().setBody("""{"code":200,"data":"$cipher"}""")
    }
    private fun client(server: MockWebServer, gate: NetworkAdmissionGate = NetworkAdmissionGate(),
        language: () -> String? = { null }, generation: () -> Long = { 0 }, cache: JsonResponseCache? = null,
        cacheGeneration: () -> String = { "" }): JmxApiClient = JmxApiClient(
        JmxHttpClient(ApiEndpointManager(listOf(server.url("/").toString())),
            tokenProvider = ApiTokenProvider(object : ApiClock { override fun nowSeconds() = 1L }),
            retryPolicy = DefaultRetryPolicy(maxAttempts = 1), queryLanguageProvider = language, admissionGate = gate),
        workGeneration = generation, responseCache = cache, cachePolicy = DefaultResponseCachePolicy(), cacheGeneration = cacheGeneration)

    @Test fun synchronousSettingStyleProbeCanSucceedWhileAsyncBusinessWaits() = runBlocking {
        val held = CountDownLatch(5); val release = CountDownLatch(1)
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/slow") { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return MockResponse().setBody("ok")
            }
        }; start() }
        try {
            val http = OkHttpClient()
            val requests = (1..5).map { async(Dispatchers.IO) { http.newCall(Request.Builder().url(server.url("/slow")).build()).awaitConsumedResponse { it.body.string() } } }
            assertTrue(withContext(Dispatchers.IO) { held.await(3, TimeUnit.SECONDS) })
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { http.newCall(Request.Builder().url(server.url("/fast")).build()).awaitConsumedResponse { it.body.string() } }
            assertEquals(1, http.dispatcher.queuedCallsCount())
            val probe = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { it.body.string() } }
            assertEquals("ok", probe); assertFalse(waiting.isCompleted)
            release.countDown(); requests.awaitAll(); assertEquals("ok", waiting.await())
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun actualBackgroundSaturationLeavesInteractiveReadUnqueued() = runBlocking {
        val held = CountDownLatch(2); val release = CountDownLatch(1)
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.queryParameter("bg") != null) { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return response()
            }
        }; start() }
        try {
            val gate = NetworkAdmissionGate(); val api = client(server, gate)
            val work = (1..5).map { n -> async { backgroundNetwork { api.requestJson(apiRequest(ApiRoute.Setting) { query("bg", n.toString()) }) } } }
            assertTrue(withContext(Dispatchers.IO) { held.await(3, TimeUnit.SECONDS) })
            assertTrue(withTimeout(1500) { api.requestJson(apiRequest(ApiRoute.Setting)) } is JmxResult.Success)
            assertEquals(2, gate.snapshot().backgroundActive)
            assertEquals(3, gate.snapshot().backgroundWaiting)
            release.countDown(); assertTrue(work.awaitAll().all { it is JmxResult.Success })
            assertEquals(0, gate.snapshot().active)
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun independentPostsAreNeverMerged() = runBlocking {
        val server = MockWebServer().apply { repeat(2) { enqueue(response().setHeadersDelay(100, TimeUnit.MILLISECONDS)) }; start() }
        try {
            val api = client(server); val request = apiRequest(ApiRoute.FavoriteAction) { form("album_id", "1") }
            assertEquals(HttpMethod.Post, request.route.method)
            val results = listOf(async { api.requestJson(request) }, async { api.requestJson(request) }).awaitAll()
            assertTrue(results.all { it is JmxResult.Success }); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun queuedRequestsKeepCapturedLanguageAndDoNotShareAcrossLanguages() = runBlocking {
        var lang = "CN"
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response("""{"lang":"${request.requestUrl?.queryParameter("lang")}"}""")
        }; start() }
        try {
            val gate = NetworkAdmissionGate(1, 1); val held = gate.acquire(); val api = client(server, gate, { lang })
            val first = async(start = CoroutineStart.UNDISPATCHED) { api.requestJson(apiRequest(ApiRoute.Setting)) }
            while (gate.snapshot().foregroundWaiting == 0) yield()
            lang = "TW"
            val second = async { api.requestJson(apiRequest(ApiRoute.Setting)) }
            yield(); held.close()
            assertEquals("CN", (first.await() as JmxResult.Success).value.asJsonObject.get("lang").asString)
            assertEquals("TW", (second.await() as JmxResult.Success).value.asJsonObject.get("lang").asString)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun sessionGenerationsDoNotShareQueuedReads() = runBlocking {
        val generation = AtomicLong()
        val server = MockWebServer().apply { repeat(2) { enqueue(response()) }; start() }
        try {
            val gate = NetworkAdmissionGate(1, 1); val held = gate.acquire(); val api = client(server, gate, generation = generation::get)
            val first = async(start = CoroutineStart.UNDISPATCHED) { api.requestJson(apiRequest(ApiRoute.Setting)) }
            while (gate.snapshot().foregroundWaiting == 0) yield()
            generation.incrementAndGet()
            val second = async { api.requestJson(apiRequest(ApiRoute.Setting)) }; yield(); held.close()
            assertTrue(first.await() is JmxResult.Success); assertTrue(second.await() is JmxResult.Success)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun oldProtocolResponseCannotBeWrittenAsNewGeneration() = runBlocking {
        var generation = "old"
        val server = MockWebServer().apply { enqueue(response().setBodyDelay(250, TimeUnit.MILLISECONDS)); start() }
        try {
            val cache = JsonResponseCache(Files.createTempDirectory("r3-cache"))
            val api = client(server, cache = cache, cacheGeneration = { generation })
            val request = apiRequest(ApiRoute.Setting)
            val result = async { api.requestJson(request) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            generation = "new"
            assertTrue(result.await() is JmxResult.Success)
            assertNull(cache.read("|${request.dedupKey()}"))
        } finally { server.shutdown() }
    }

    @Test fun bodyConsumptionRemainsCancellableAfterHeaders() = runBlocking {
        val server = MockWebServer().apply { enqueue(response().setBodyDelay(2, TimeUnit.SECONDS)); start() }
        try {
            val gate = NetworkAdmissionGate(1, 1); val api = client(server, gate)
            val call = async { api.requestJson(apiRequest(ApiRoute.Setting)) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            delay(50); withTimeout(1000) { call.cancelAndJoin() }
            withTimeout(1000) { while (gate.snapshot().active != 0) yield() }
        } finally { server.shutdown() }
    }

    @Test fun bodyBudgetRejectsUnknownLengthAndKeepsCorrectCharset() {
        val body = okhttp3.ResponseBody.Companion.run { "0123456789abcdef".toResponseBody() }
        try { body.readBoundedText(8); fail("Expected byte limit") } catch (_: java.io.IOException) {}
        val unknownLength = object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8("0123456789abcdef")
        }
        try { unknownLength.readBoundedText(8); fail("Expected unknown-length limit") } catch (_: java.io.IOException) {}
        val utf8 = okhttp3.ResponseBody.Companion.run { "韩漫".toResponseBody() }
        assertEquals("韩漫", utf8.readBoundedText(10))
    }

    @Test fun delimiterAndNullQueryKeysCannotCollide() {
        val one = apiRequest(ApiRoute.Setting) { query("a", "b&c=d") }
        val two = apiRequest(ApiRoute.Setting) { query("a", "b"); query("c", "d") }
        assertNotEquals(one.dedupKey(), two.dedupKey())
        assertEquals(apiRequest(ApiRoute.Setting).dedupKey(), apiRequest(ApiRoute.Setting) { query("absent", null as String?) }.dedupKey())
    }
}
