package app.prismia.plus.core.network

import app.prismia.plus.core.cache.JsonResponseCache
import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.protocol.ApiClock
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ResponseCacheTest {
    @Test
    fun responseStartedBeforeGenerationChangeCannotPopulateTheNewGeneration() = runBlocking {
        val generation = java.util.concurrent.atomic.AtomicReference("old")
        val client = createClient(language = "CN", generation = generation::get)
        server.enqueue(encryptedResponse("""{"value":"old"}""").setHeadersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { client.requestJson(apiRequest(ApiRoute.Promote)) }
        assertTrue(kotlinx.coroutines.withContext(Dispatchers.IO) {
            server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        } != null)
        generation.set("new")
        pending.await()
        server.enqueue(encryptedResponse("""{"value":"new"}"""))
        val fresh = client.requestJson(apiRequest(ApiRoute.Promote))
        assertEquals("new", fresh.valueOrFail().asJsonObject["value"].asString)
        assertEquals(2, server.requestCount)
    }

    private lateinit var server: MockWebServer
    private lateinit var cacheDirectory: Path
    private val revalidationJob = SupervisorJob()
    private val revalidationScope = CoroutineScope(revalidationJob + Dispatchers.IO)

    /** 缓存写入与年龄判定共用这一个时钟，测试直接推进它来模拟条目变旧。 */
    private var nowMillis: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDirectory = Files.createTempDirectory("jmx-response-cache-test")
    }

    @After
    fun tearDown() {
        revalidationJob.cancel()
        server.shutdown()
        cacheDirectory.toFile().deleteRecursively()
    }

    @Test
    fun freshEntryIsServedWithoutHittingNetwork() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(encryptedResponse())

        val first = client.requestJson(apiRequest(ApiRoute.Promote))
        nowMillis += 1_000L
        val second = client.requestJson(apiRequest(ApiRoute.Promote))

        assertTrue(first is JmxResult.Success)
        assertTrue(second is JmxResult.Success)
        assertEquals(first.valueOrFail(), second.valueOrFail())
        assertEquals(1, server.requestCount)
        assertEquals(1, client.cacheHitCount)
        assertEquals(0, client.staleServedCount)
    }

    @Test
    fun staleEntryIsServedImmediatelyThenRevalidatedInBackground() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(encryptedResponse(payload = """{"page":1}"""))
        client.requestJson(apiRequest(ApiRoute.Promote))

        // Promote 的新鲜期是 3 分钟，可容忍过期是 3 天：这里落在两者之间。
        nowMillis += 10 * 60 * 1000L
        server.enqueue(encryptedResponse(payload = """{"page":2}"""))
        val stale = client.requestJson(apiRequest(ApiRoute.Promote))

        // 返回的仍是旧内容，调用方没有为这次刷新等待。
        assertEquals("""{"page":1}""", stale.valueOrFail().toString())
        assertEquals(1, client.staleServedCount)

        revalidationScope.coroutineContext.job.children.toList().joinAll()
        assertEquals(2, server.requestCount)

        // 后台校验已回写，下一次读到的是新内容。
        nowMillis += 1_000L
        val refreshed = client.requestJson(apiRequest(ApiRoute.Promote))
        assertEquals("""{"page":2}""", refreshed.valueOrFail().toString())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun expiredEntryFallsBackToBlockingNetworkFetch() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(encryptedResponse(payload = """{"page":1}"""))
        client.requestJson(apiRequest(ApiRoute.Promote))

        // 超过可容忍过期时长（3 天），旧内容不再采用。
        nowMillis += 4 * 24 * 60 * 60 * 1000L
        server.enqueue(encryptedResponse(payload = """{"page":2}"""))
        val result = client.requestJson(apiRequest(ApiRoute.Promote))

        assertEquals("""{"page":2}""", result.valueOrFail().toString())
        assertEquals(2, server.requestCount)
    }

    /**
     * 隐式语言会在请求快照中固化，缓存命名空间仍隔离各自的语言偏好。
     * 切到繁體的用户不能读到简体请求留下的缓存。
     */
    @Test
    fun differentContentLanguagesDoNotShareCacheEntries() = runBlocking {
        val simplified = createClient(language = "CN")
        val traditional = createClient(language = "TW")
        server.enqueue(encryptedResponse(payload = """{"lang":"CN"}"""))
        server.enqueue(encryptedResponse(payload = """{"lang":"TW"}"""))

        val cn = simplified.requestJson(apiRequest(ApiRoute.Promote))
        val tw = traditional.requestJson(apiRequest(ApiRoute.Promote))

        assertEquals("""{"lang":"CN"}""", cn.valueOrFail().toString())
        assertEquals("""{"lang":"TW"}""", tw.valueOrFail().toString())
        assertEquals(2, server.requestCount)
        assertEquals(0, traditional.cacheHitCount)
    }

    @Test
    fun cacheGenerationChangeDiscardsPreviousEntries() = runBlocking {
        var generation = "1.0.0"
        val client = createClient(language = "CN", generation = { generation })
        server.enqueue(encryptedResponse(payload = """{"page":1}"""))
        client.requestJson(apiRequest(ApiRoute.Promote))

        generation = "1.1.0"
        nowMillis += 1_000L
        server.enqueue(encryptedResponse(payload = """{"page":2}"""))
        val result = client.requestJson(apiRequest(ApiRoute.Promote))

        assertEquals("""{"page":2}""", result.valueOrFail().toString())
        assertEquals(2, server.requestCount)
        assertEquals(0, client.cacheHitCount)
    }

    /** 未列入策略的路由（这里是带个人收藏态的 album）必须每次都出网。 */
    @Test
    fun routesOutsidePolicyAreNeverCached() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(encryptedResponse())
        server.enqueue(encryptedResponse())

        client.requestJson(apiRequest(ApiRoute.Album) { query("id", "1") })
        nowMillis += 1_000L
        client.requestJson(apiRequest(ApiRoute.Album) { query("id", "1") })

        assertEquals(2, server.requestCount)
        assertEquals(0, client.cacheHitCount)
    }

    @Test
    fun failedResponsesAreNotCached() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(encryptedResponse(payload = """{"page":1}"""))

        val failure = client.requestJson(apiRequest(ApiRoute.Promote))
        nowMillis += 1_000L
        val success = client.requestJson(apiRequest(ApiRoute.Promote))

        assertTrue(failure is JmxResult.Failure)
        assertEquals("""{"page":1}""", success.valueOrFail().toString())
    }

    private fun createClient(
        language: String,
        generation: () -> String = { "1.0.0" }
    ): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = TOKEN_TIMESTAMP_SECONDS
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
        return JmxApiClient(
            httpClient = JmxHttpClient(
                endpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
                tokenProvider = tokenProvider,
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                queryLanguageProvider = { language }
            ),
            responseCache = JsonResponseCache(directory = cacheDirectory, nowMillis = { nowMillis }),
            cachePolicy = DefaultResponseCachePolicy(),
            revalidationScope = revalidationScope,
            cacheNamespace = { language },
            cacheGeneration = generation,
            nowMillis = { nowMillis }
        )
    }

    private fun encryptedResponse(payload: String = """{"ok":true}"""): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            payload,
            JmxHash.md5Hex("$TOKEN_TIMESTAMP_SECONDS${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private fun <T> JmxResult<T>.valueOrFail(): T = when (this) {
        is JmxResult.Success -> value
        is JmxResult.Failure -> throw AssertionError("期望成功，实际失败：${error.message}")
    }

    private companion object {
        const val TOKEN_TIMESTAMP_SECONDS = 1L
    }
}
