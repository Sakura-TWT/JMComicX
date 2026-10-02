package app.prismia.plus.core.network

import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.protocol.ApiClock
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JmxApiClientDedupTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(RESPONSE_DELAY_MILLIS)
                return encryptedResponse("""{"ok":true}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun concurrentIdenticalRequestsShareSingleNetworkCall() = runBlocking {
        val client = createClient()
        val request = apiRequest(ApiRoute.Setting) { query("t", "1") }

        val results = (1..3).map { async { client.requestJson(request) } }.awaitAll()

        assertEquals(3, results.count { it is JmxResult.Success })
        assertEquals(1, server.requestCount)
        assertEquals(2, client.deduplicatedRequestCount)
    }

    @Test
    fun differentQueriesAreNotDeduplicated() = runBlocking {
        val client = createClient()

        val results = listOf(
            apiRequest(ApiRoute.Setting) { query("t", "1") },
            apiRequest(ApiRoute.Setting) { query("t", "2") },
        ).map { async { client.requestJson(it) } }.awaitAll()

        assertEquals(2, results.count { it is JmxResult.Success })
        assertEquals(2, server.requestCount)
        assertEquals(0, client.deduplicatedRequestCount)
    }

    @Test
    fun sequentialRepeatsAfterCompletionAreNotDeduplicated() = runBlocking {
        val client = createClient()
        val request = apiRequest(ApiRoute.Setting) { query("t", "1") }

        val first = client.requestJson(request)
        val second = client.requestJson(request)

        assertTrue(first is JmxResult.Success)
        assertTrue(second is JmxResult.Success)
        assertEquals(2, server.requestCount)
        assertEquals(0, client.deduplicatedRequestCount)
    }

    @Test
    fun httpClientRecordsMetricsForSuccessAndFailure() = runBlocking {
        val recorder = RequestMetricsRecorder()
        val client = JmxApiClient(
            JmxHttpClient(
                endpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
                tokenProvider = fixedClockTokenProvider(),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                requestMetricsRecorder = recorder
            )
        )

        client.requestJson(apiRequest(ApiRoute.Setting) { query("t", "1") })

        val metrics = recorder.snapshot()
        assertEquals(1, metrics.size)
        assertEquals("/setting", metrics.single().route)
        assertEquals(server.hostName, metrics.single().endpointHost)
        assertTrue(metrics.single().success)

        recorder.clear()
        assertEquals(0, recorder.snapshot().size)
    }

    private fun createClient(): JmxApiClient {
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
                tokenProvider = fixedClockTokenProvider(),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
    }

    private fun fixedClockTokenProvider(): ApiTokenProvider {
        return ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = 1L
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
    }

    private fun encryptedResponse(dataJson: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            dataJson,
            JmxHash.md5Hex("1${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private companion object {
        const val RESPONSE_DELAY_MILLIS = 300L
    }
}
