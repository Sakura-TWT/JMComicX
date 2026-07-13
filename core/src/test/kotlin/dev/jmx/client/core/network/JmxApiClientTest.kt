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

    private fun createClient(ts: Long): JmxApiClient {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
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
                maxAttempts = 1
            )
        )
    }
}
