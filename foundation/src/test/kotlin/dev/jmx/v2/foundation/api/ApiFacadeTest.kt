package dev.jmx.v2.foundation.api

import dev.jmx.v2.foundation.crypto.AesEcbPkcs7
import dev.jmx.v2.foundation.crypto.JmxHash
import dev.jmx.v2.foundation.network.ApiEndpointManager
import dev.jmx.v2.foundation.network.JmxApiClient
import dev.jmx.v2.foundation.network.JmxHttpClient
import dev.jmx.v2.foundation.protocol.ApiClock
import dev.jmx.v2.foundation.protocol.ApiTokenProvider
import dev.jmx.v2.foundation.protocol.JmxProtocolConstants
import dev.jmx.v2.foundation.protocol.MutableApiVersionProvider
import dev.jmx.v2.foundation.result.JmxResult
import dev.jmx.v2.foundation.session.InMemoryCookieStore
import dev.jmx.v2.foundation.session.SessionManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiFacadeTest {
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
    fun settingApiUpdatesRuntimeVersion() {
        val versionProvider = MutableApiVersionProvider("1.0.0")
        server.enqueue(encryptedResponse("""{"jm3_version":"2.0.26","img_host":"https://img.test","app_shunts":[{"id":"1","name":"线路 1"}]}"""))
        val settingApi = SettingApi(createClient(versionProvider = versionProvider), versionProvider)

        val result = kotlinx.coroutines.runBlocking { settingApi.fetchSetting() }

        assertTrue(result is JmxResult.Success)
        assertEquals("2.0.26", versionProvider.current())
        assertEquals("https://img.test", (result as JmxResult.Success).value.imageHost)
    }

    @Test
    fun userApiInstallsAvsCookieAfterLogin() {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        server.enqueue(encryptedResponse("""{"s":"avs-value","uid":"1"}"""))
        val userApi = UserApi(createClient(endpointManager = endpointManager), endpointManager, session)

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        assertEquals("avs-value", session.cookies().single().value)
        val recorded = server.takeRequest()
        assertEquals("username=user&password=pass", recorded.body.readUtf8())
    }

    private fun createClient(
        endpointManager: ApiEndpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
        versionProvider: MutableApiVersionProvider = MutableApiVersionProvider(JmxProtocolConstants.DefaultApiVersion)
    ): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = TS
            },
            apiVersionProvider = versionProvider
        )
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = endpointManager,
                tokenProvider = tokenProvider,
                maxAttempts = 1
            )
        )
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
    }
}
