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
import dev.jmx.client.core.protocol.MutableApiVersionProvider
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.session.InMemoryCookieStore
import dev.jmx.client.core.session.SessionManager
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

    @Test
    fun albumApiParsesFullAlbumDetail() {
        server.enqueue(
            encryptedResponse(
                """
                {
                  "id":123,
                  "name":"album",
                  "description":"desc",
                  "author":["alice","bob"],
                  "total_views":99,
                  "likes":10,
                  "comment_total":3,
                  "tags":["tag"],
                  "actors":["role"],
                  "works":["work"],
                  "is_favorite":true,
                  "liked":false,
                  "related_list":[{"id":"8","name":"related","author":"carol","image":"cover.jpg"}],
                  "series":[{"id":"9","name":"第1话","sort":"1"}],
                  "series_id":"7",
                  "price":"5",
                  "purchased":true
                }
                """.trimIndent()
            )
        )
        val albumApi = AlbumApi(createClient())

        val result = kotlinx.coroutines.runBlocking { albumApi.detailFull("123") }

        assertTrue(result is JmxResult.Success)
        val detail = (result as JmxResult.Success).value
        assertEquals("123", detail.id)
        assertEquals("album", detail.name)
        assertEquals(listOf("alice", "bob"), detail.authors)
        assertEquals(99, detail.totalViews)
        assertEquals(true, detail.isFavorite)
        assertEquals("related", detail.related.single().name)
        assertEquals("第1话", detail.series.single().name)
        assertEquals(5, detail.price)
        assertEquals(true, detail.purchased)
        assertEquals("/album?id=123", server.takeRequest().path)
    }

    @Test
    fun albumApiDetailKeepsSummaryCompatibility() {
        server.enqueue(encryptedResponse("""{"id":"321","name":"summary","author":"solo","images":12}"""))
        val albumApi = AlbumApi(createClient())

        val result = kotlinx.coroutines.runBlocking { albumApi.detail("321") }

        assertTrue(result is JmxResult.Success)
        val summary = (result as JmxResult.Success).value
        assertEquals("321", summary.id)
        assertEquals("summary", summary.name)
        assertEquals("solo", summary.author)
        assertEquals(12, summary.imageCount)
        assertEquals("/album?id=321", server.takeRequest().path)
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
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
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
