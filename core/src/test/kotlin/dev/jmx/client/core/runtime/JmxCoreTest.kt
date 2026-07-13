package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.session.InMemoryCookieStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JmxCoreTest {
    private lateinit var server: MockWebServer
    private lateinit var domainServer: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        domainServer = MockWebServer()
        domainServer.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        domainServer.shutdown()
    }

    @Test
    fun coreUsesStoredProtocolStateForEndpointAndVersion() {
        val ts = 1700566805L
        val keyValueStore = InMemoryKeyValueStore(
            mapOf(
                "protocol.api.version" to "1.0.0",
                "protocol.api.hosts" to server.url("/").toString()
            )
        )
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = keyValueStore,
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.1.0","img_host":"https://img.test","app_shunts":[]}"""))

        val result = kotlinx.coroutines.runBlocking { core.settingApi.fetchSetting() }

        assertTrue(result is JmxResult.Success)
        assertEquals("2.1.0", core.apiVersionProvider.current())
        assertEquals("2.1.0", core.protocolStateStore.apiVersion())
        val recorded = server.takeRequest()
        assertEquals("/setting", recorded.path)
        assertEquals("1700566805,1.0.0", recorded.headers["tokenparam"])
    }

    @Test
    fun coreSharesSessionAcrossUserApiAndCookieStore() {
        val ts = 1700566805L
        val cookieStore = InMemoryCookieStore()
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                cookieStore = cookieStore,
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
        server.enqueue(encryptedResponse(ts, """{"s":"avs-value","uid":"1"}"""))

        val result = kotlinx.coroutines.runBlocking { core.userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        assertEquals("avs-value", core.sessionManager.cookies().single().value)
        assertEquals("username=user&password=pass", server.takeRequest().body.readUtf8())
    }

    @Test
    fun initializerRefreshesDomainsThenFetchesSetting() {
        val ts = 1700566805L
        domainServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(encryptDomain("""{"Server":["${server.url("/")}"]}"""))
        )
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.2.0","img_host":"https://img.test","app_shunts":[]}"""))
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to "https://old.test")),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )

        val result = kotlinx.coroutines.runBlocking { core.initializer.initialize() }

        assertTrue(result.domainRefresh is InitStepResult.Success)
        assertTrue(result.settingFetch is InitStepResult.Success)
        assertTrue(result.isFullySuccessful)
        assertEquals("2.2.0", core.protocolStateStore.apiVersion())
        assertEquals(server.url("/").toString(), core.protocolStateStore.apiHosts().single())
        assertEquals("/setting", server.takeRequest().path)
    }

    @Test
    fun initializerKeepsGoingWhenDomainRefreshFails() {
        val ts = 1700566805L
        domainServer.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.3.0","img_host":"https://img.test","app_shunts":[]}"""))
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to server.url("/").toString())),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )

        val result = kotlinx.coroutines.runBlocking { core.initializer.initialize() }

        assertTrue(result.domainRefresh is InitStepResult.Failure)
        assertTrue(result.settingFetch is InitStepResult.Success)
        assertTrue(!result.isFullySuccessful)
        assertEquals("2.3.0", core.protocolStateStore.apiVersion())
        assertEquals("/setting", server.takeRequest().path)
    }

    @Test
    fun healthSnapshotReportsRuntimeState() {
        val domainUrls = listOf("https://domain-a.test/domains", "https://domain-b.test/domains")
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf(
                        "protocol.api.version" to "2.5.0",
                        "protocol.api.hosts" to "https://api-a.test\nhttps://api-b.test"
                    )
                ),
                downloadConcurrency = 7,
                domainServerUrls = domainUrls
            )
        )
        core.endpointManager.markFailure("https://api-a.test/".toHttpUrl(), "timeout")
        core.sessionManager.installAvsCookie("https://api-a.test", "secret")

        val health = core.healthSnapshot()

        assertEquals("2.5.0", health.apiVersion)
        assertEquals(2, health.endpoints.size)
        assertEquals("https://api-a.test/", health.endpoints[0].url)
        assertEquals(1, health.endpoints[0].failureCount)
        assertEquals("timeout", health.endpoints[0].lastFailureMessage)
        assertEquals(1, health.cookieCount)
        assertEquals(domainUrls, health.domainServerUrls)
        assertEquals(7, health.downloadConcurrency)
    }

    private fun encryptedResponse(ts: Long, dataJson: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            dataJson,
            JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private fun encryptDomain(json: String): String {
        return AesEcbPkcs7.encryptStringToBase64(
            json,
            JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret)
        )
    }

    private fun fixedClock(ts: Long): ApiClock {
        return object : ApiClock {
            override fun nowSeconds(): Long = ts
        }
    }
}
