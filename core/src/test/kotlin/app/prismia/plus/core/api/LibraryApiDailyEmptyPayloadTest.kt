package app.prismia.plus.core.api

import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.network.ApiEndpointManager
import app.prismia.plus.core.network.DefaultRetryPolicy
import app.prismia.plus.core.network.JmxApiClient
import app.prismia.plus.core.network.JmxHttpClient
import app.prismia.plus.core.protocol.ApiClock
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 签到接口空载荷契约（code=200,data=null）：
 * - 单线路连续为空 -> Success(null)，且本进程后续不再交叉验证
 * - 多线路一条空、一条有效 -> 直接使用有效数据
 * - 非 200 / 缺 data 字段 / 非对象 data -> 明确失败，不伪装成无活动
 */
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
    fun dailyInfoTreatsConsistentEmptyPayloadAsNoActiveEvent() = runBlocking {
        val api = LibraryApi(createClient(singleLine = true))
        primary.enqueue(emptyPayloadResponse())
        primary.enqueue(emptyPayloadResponse())

        val first = api.dailyInfo("42")
        assertTrue(first is JmxResult.Success)
        assertNull((first as JmxResult.Success).value)
        assertEquals(2, primary.requestCount)

        // 会话内已跨线路确认：再次遇到空载荷直接返回，不再补发交叉验证请求
        primary.enqueue(emptyPayloadResponse())
        val second = api.dailyInfo("42")
        assertTrue(second is JmxResult.Success)
        assertNull((second as JmxResult.Success).value)
        assertEquals(3, primary.requestCount)
    }

    @Test
    fun dailyInfoRecoversWhenAlternateLineHasData() = runBlocking {
        val api = LibraryApi(createClient(singleLine = false))
        primary.enqueue(emptyPayloadResponse())
        secondary.enqueue(encryptedResponse(DAILY_JSON))

        val result = api.dailyInfo("42")

        assertTrue(result is JmxResult.Success)
        assertNotNull((result as JmxResult.Success).value)
        assertEquals(1, primary.requestCount)
        assertEquals(1, secondary.requestCount)
        assertEquals("/daily?user_id=42", secondary.takeRequest().path)
    }

    @Test
    fun dailyInfoKeepsAlternateLineFailureAsError() = runBlocking {
        val api = LibraryApi(createClient(singleLine = false))
        primary.enqueue(emptyPayloadResponse())
        secondary.enqueue(emptyPayloadResponse())

        val result = api.dailyInfo("42")

        assertTrue(result is JmxResult.Success)
        assertNull((result as JmxResult.Success).value)
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
