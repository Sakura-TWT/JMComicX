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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Empty data stays a failure so the shared account layer can perform bounded recovery. */
class LibraryApiDailyEmptyPayloadTest {
    private lateinit var primary: MockWebServer
    private lateinit var secondary: MockWebServer

    @Before
    fun setUp() {
        primary = MockWebServer()
        primary.start()
        secondary = MockWebServer()
        secondary.start()
    }

    @After
    fun tearDown() {
        primary.shutdown()
        secondary.shutdown()
    }

    @Test
    fun dailyInfoParsesActiveEvent() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(encryptedResponse(DAILY_JSON))

        val result = api.dailyInfo("42")

        assertTrue(result is JmxResult.Success)
        assertEquals(7, (result as JmxResult.Success).value!!.dailyId)
        assertEquals("/daily?user_id=42", primary.takeRequest().path)
    }

    @Test
    fun dailyInfoKeepsEmptyPayloadAsRecoverableFailure() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(emptyPayloadResponse())
        val first = api.dailyInfo("42")
        assertTrue(first is JmxResult.Failure)
        assertTrue((first as JmxResult.Failure).error is JmxError.EmptyData)
        assertEquals(1, primary.requestCount)

        // No process-wide "no event" memory: a later fresh session can see an event.
        primary.enqueue(encryptedResponse(DAILY_JSON))
        assertTrue(api.dailyInfo("42") is JmxResult.Success)
        assertEquals(2, primary.requestCount)
    }

    @Test
    fun emptyPayloadDoesNotDoApiLayerSessionRecoveryOrExtraRequests() = runBlocking {
        val api = LibraryApi(createClient(singleLine = false))
        primary.enqueue(emptyPayloadResponse())
        val result = api.dailyInfo("42")
        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.EmptyData)
        assertEquals(1, primary.requestCount)
        assertEquals(0, secondary.requestCount)
    }

    @Test
    fun emptyObjectIsAmbiguousNotAnInactiveEvent() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(encryptedResponse("{}"))
        val result = api.dailyInfo("42")
        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.EmptyData)
    }
    @Test
    fun dailyInfoFailsOnBusinessErrorCode() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"code":1005,"msg":"活动已结束"}""")
        )

        val result = api.dailyInfo("42")

        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.Api)
        assertEquals(1005, (result.error as JmxError.Api).code)
    }

    @Test
    fun dailyInfoFailsWhenDataFieldMissing() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200}"""))

        val result = api.dailyInfo("42")

        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.Schema)
    }

    @Test
    fun dailyInfoFailsWhenDataIsNotObject() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(encryptedResponse("""["not","an","object"]"""))

        val result = api.dailyInfo("42")

        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.Schema)
    }

    private fun createClient(singleLine: Boolean): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = TS
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
        val hosts = if (singleLine) {
            listOf(primary.url("/").toString())
        } else {
            listOf(primary.url("/").toString(), secondary.url("/").toString())
        }
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = ApiEndpointManager(hosts),
                tokenProvider = tokenProvider,
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
    }

    private fun emptyPayloadResponse(): MockResponse {
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":null}""")
    }

    private fun encryptedResponse(dataJson: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            dataJson,
            JmxHash.md5Hex("$TS${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private companion object {
        const val TS = 1700566805L
        const val DAILY_JSON =
            """{"daily_id":7,"event_name":"daily","record":[[{"date":"2026-07-14","signed":false,"bonus":false}]]}"""
    }
}
