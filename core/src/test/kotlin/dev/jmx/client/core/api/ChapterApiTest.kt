package dev.jmx.client.core.api

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.ApiEndpointManager
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.JmxHttpClient
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChapterApiTest {
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
    fun templateParseFailureCarriesTextExchange() {
        val ts = 1700566805L
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><script>const result = {\"images\":[]};</script></html>")
        )
        val api = createApi(ts)

        val result = kotlinx.coroutines.runBlocking {
            api.template(chapterId = "123", shunt = "1", timestampSeconds = ts)
        }

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        val exchange = (error as JmxError.Schema).exchange
        requireNotNull(exchange)
        assertEquals("/chapter_view_template", exchange.route)
        assertTrue(exchange.requestUrl.contains("id=123"))
        assertTrue(exchange.requestUrl.contains("app_img_shunt=1"))
        assertEquals(200, exchange.statusCode)
        assertEquals("text/html", exchange.contentType)
        assertEquals(ts, exchange.tokenTimestampSeconds)
        assertTrue(exchange.bodySample.contains("images"))
    }

    @Test
    fun detailParsesPhotoAndCachesScrambleId() {
        val ts = 1700566805L
        val plain = """
            {
              "id":"9001",
              "name":"ch-1",
              "series_id":"8001",
              "sort":2,
              "scramble_id":220980,
              "page_arr":["00001.webp","00002.webp"],
              "data_original_domain":"cdn-msp.jmapiproxy1.cc"
            }
        """.trimIndent()
        server.enqueue(encryptedJson(ts, plain))
        val api = createApi(ts)

        val result = kotlinx.coroutines.runBlocking { api.detail("JM9001") }

        assertTrue(result is JmxResult.Success)
        val photo = (result as JmxResult.Success).value
        assertEquals("9001", photo.id)
        assertEquals("8001", photo.albumId)
        assertEquals(2, photo.pageArr.size)
        assertEquals(220980, photo.scrambleId)
        assertEquals(220980, api.cachedScrambleId("9001", "8001"))
        val recorded = server.takeRequest()
        assertEquals("/chapter?id=9001", recorded.path)
    }

    private fun createApi(ts: Long): ChapterApi {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = ts
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val httpClient = JmxHttpClient(
            endpointManager = endpointManager,
            tokenProvider = tokenProvider,
            retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
        )
        return ChapterApi(JmxApiClient(httpClient))
    }

    private fun encryptedJson(ts: Long, plain: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            plain = plain,
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse()
            .setResponseCode(200)
            .setBody("""{"code":200,"data":"$encrypted"}""")
    }
}
