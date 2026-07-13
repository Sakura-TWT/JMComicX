package dev.jmx.client.core.network

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.cache.ProtocolStateStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.session.InMemoryCookieStore
import dev.jmx.client.core.session.SessionManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DomainServerTest {
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
    fun decodesDomainServerPayload() {
        val encrypted = encrypt("""{"Server":["www.a.test","www.a.test","https://www.b.test"]}""")
        val decoder = DomainServerDecoder()

        val result = decoder.decode("\uFEFF$encrypted")

        assertTrue(result is JmxResult.Success)
        assertEquals(listOf("www.a.test", "https://www.b.test"), (result as JmxResult.Success).value.apiHosts)
    }

    @Test
    fun refreshesEndpointManagerFromServerAndPersistsHosts() {
        val encrypted = encrypt("""{"Server":["www.a.test","https://www.b.test"]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(encrypted))
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val manager = ApiEndpointManager(
            initialHosts = listOf("old.test"),
            protocolStateStore = stateStore
        )
        val refresher = DomainRefresher(
            endpointManager = manager,
            serverUrls = listOf(server.url("/domains").toString())
        )

        val result = kotlinx.coroutines.runBlocking { refresher.refresh() }

        assertTrue(result is JmxResult.Success)
        assertEquals("https://www.a.test/", manager.all()[0].url.toString())
        assertEquals("https://www.b.test/", manager.all()[1].url.toString())
        assertEquals(listOf("https://www.a.test/", "https://www.b.test/"), stateStore.apiHosts())
    }

    @Test
    fun refreshSyncsExistingAvsCookieToNewEndpoints() {
        val encrypted = encrypt("""{"Server":["www.a.test","https://www.b.test"]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(encrypted))
        val manager = ApiEndpointManager(initialHosts = listOf("old.test"))
        val cookieStore = InMemoryCookieStore()
        val sessionManager = SessionManager(cookieStore)
        sessionManager.installAvsCookie("https://old.test", "secret")
        val refresher = DomainRefresher(
            endpointManager = manager,
            serverUrls = listOf(server.url("/domains").toString()),
            sessionManager = sessionManager
        )

        val result = kotlinx.coroutines.runBlocking { refresher.refresh() }

        assertTrue(result is JmxResult.Success)
        assertEquals(1, cookieStore.load("https://old.test/album".toHttpUrl()).size)
        assertEquals(1, cookieStore.load("https://www.a.test/album".toHttpUrl()).size)
        assertEquals(1, cookieStore.load("https://www.b.test/album".toHttpUrl()).size)
        assertEquals(0, cookieStore.load("https://sub.www.a.test/album".toHttpUrl()).size)
    }

    @Test
    fun refreshFailureReportsEachAttemptedServer() {
        val first = MockWebServer()
        val second = MockWebServer()
        first.start()
        second.start()
        try {
            first.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            second.enqueue(MockResponse().setResponseCode(500).setBody("broken"))
            val manager = ApiEndpointManager(listOf("old.test"))
            val refresher = DomainRefresher(
                endpointManager = manager,
                serverUrls = listOf(first.url("/domains").toString(), second.url("/domains").toString())
            )

            val result = kotlinx.coroutines.runBlocking { refresher.refresh() }

            assertTrue(result is JmxResult.Failure)
            val error = (result as JmxResult.Failure).error
            assertTrue(error is JmxError.Domain)
            assertTrue(error.message.contains(first.url("/domains").toString()))
            assertTrue(error.message.contains(second.url("/domains").toString()))
            assertTrue(error.message.contains("503"))
            assertTrue(error.message.contains("500"))
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    private fun encrypt(json: String): String {
        return AesEcbPkcs7.encryptStringToBase64(
            plain = json,
            key = JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret)
        )
    }
}
