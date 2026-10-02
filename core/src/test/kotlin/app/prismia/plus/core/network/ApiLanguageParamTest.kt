package app.prismia.plus.core.network

import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.protocol.ApiClock
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiLanguageParamTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun appendsLangToGetRequestsWhenLanguageConfigured() = runBlocking {
        val client = createClient(language = "TW")
        server.enqueue(encryptedResponse())

        val result = client.requestJson(apiRequest(ApiRoute.Setting) { query("t", "1") })

        assertTrue(result is JmxResult.Success)
        assertEquals("/setting?t=1&lang=TW", server.takeRequest().path)
    }

    // 服务端在缺省 lang 时返回繁體，因此简体必须显式发送 lang=CN，
    // 不能靠省略参数落到"默认简体"上——官方客户端也是无条件附加 lang。
    @Test
    fun appendsSimplifiedLangExplicitly() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(encryptedResponse())

        val result = client.requestJson(apiRequest(ApiRoute.Setting) { query("t", "1") })

        assertTrue(result is JmxResult.Success)
        assertEquals("/setting?t=1&lang=CN", server.takeRequest().path)
    }

    @Test
    fun noLangParamWhenLanguageNotConfigured() = runBlocking {
        val client = createClient(language = null)
        server.enqueue(encryptedResponse())

        val result = client.requestJson(apiRequest(ApiRoute.Setting) { query("t", "1") })

        assertTrue(result is JmxResult.Success)
        assertEquals("/setting?t=1", server.takeRequest().path)
    }

    @Test
    fun doesNotOverrideExplicitLangAndSkipsPostRequests() = runBlocking {
        val client = createClient(language = "TW")
        server.enqueue(encryptedResponse())
        server.enqueue(encryptedResponse())

        client.requestJson(apiRequest(ApiRoute.Setting) { query("lang", "CN") })
        client.requestJson(apiRequest(ApiRoute.Login) { form("username", "u") })

        // t 是 ApiRoute.Setting 的破缓存参数，由 buildUrl 追加（固定时钟下为 1）。
        assertEquals("/setting?lang=CN&t=1", server.takeRequest().path)
        assertFalse(server.takeRequest().path!!.contains("lang"))
    }

    // t 只在 ApiRoute.cacheBuster 为真的路由上追加，且请求已显式带 t 时不覆盖。
    @Test
    fun appendsCacheBusterOnlyForCacheBustingRoutes() = runBlocking {
        val client = createClient(language = "CN")
        server.enqueue(encryptedResponse())
        server.enqueue(encryptedResponse())

        client.requestJson(apiRequest(ApiRoute.Album) { query("id", "1") })
        client.requestJson(apiRequest(ApiRoute.Setting))

        assertEquals("/album?id=1&lang=CN", server.takeRequest().path)
        assertEquals("/setting?lang=CN&t=1", server.takeRequest().path)
    }

    private fun createClient(language: String?): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = 1L
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
                tokenProvider = tokenProvider,
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                queryLanguageProvider = { language }
            )
        )
    }

    private fun encryptedResponse(): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            """{"ok":true}""",
            JmxHash.md5Hex("1${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }
}
