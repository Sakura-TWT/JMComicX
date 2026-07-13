package dev.jmx.client.core.network

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JmxApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var secondaryServer: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secondaryServer = MockWebServer()
        secondaryServer.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        secondaryServer.shutdown()
    }

    @Test
    fun sendsTokenHeadersAndDecodesEncryptedJson() {
        val ts = 1700566805L
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            plain = """{"name":"demo"}""",
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}"""))
        val client = createClient(ts)

        val result = kotlinx.coroutines.runBlocking {
            client.requestJson(
                ApiRequest(
                    route = ApiRoute.Album,
                    query = mapOf("id" to "123")
                )
            )
        }

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals("demo", value.asJsonObject["name"].asString)
        val recorded = server.takeRequest()
        assertEquals("/album?id=123", recorded.path)
        assertEquals("1700566805,2.0.26", recorded.headers["tokenparam"])
        assertEquals(JmxHash.md5Hex("$ts${JmxProtocolConstants.AppTokenSecret}"), recorded.headers["token"])
    }

    @Test
    fun chapterTemplateRequestUsesChapterSecretAndReturnsText() {
        val ts = 1700566805L
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
        val client = createClient(ts)

        val result = kotlinx.coroutines.runBlocking {
            client.requestText(ApiRequest(route = ApiRoute.ChapterViewTemplate))
        }

        assertTrue(result is JmxResult.Success)
        val recorded = server.takeRequest()
        assertEquals(JmxHash.md5Hex("$ts${JmxProtocolConstants.ChapterTokenSecret}"), recorded.headers["token"])
    }

    @Test
    fun retriesRetryableHttpFailureOnNextEndpoint() {
        val ts = 1700566805L
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            plain = """{"name":"fallback"}""",
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        secondaryServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}"""))
        val endpointManager = ApiEndpointManager(
            listOf(server.url("/").toString(), secondaryServer.url("/").toString()),
            maxFailuresBeforeDemote = 1
        )
        val client = createClient(ts, endpointManager = endpointManager, maxAttempts = 2)

        val result = kotlinx.coroutines.runBlocking {
            client.requestJson(ApiRequest(route = ApiRoute.Album))
        }

        assertTrue(result is JmxResult.Success)
        assertEquals("fallback", (result as JmxResult.Success).value.asJsonObject["name"].asString)
        assertEquals("/album", server.takeRequest().path)
        assertEquals("/album", secondaryServer.takeRequest().path)
    }

    private fun createClient(
        ts: Long,
        endpointManager: ApiEndpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
        maxAttempts: Int = 1
    ): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = ts
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = endpointManager,
                tokenProvider = tokenProvider,
                retryPolicy = DefaultRetryPolicy(maxAttempts = maxAttempts)
            )
        )
    }
}
