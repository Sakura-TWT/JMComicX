package dev.jmx.client.core.api

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
}
