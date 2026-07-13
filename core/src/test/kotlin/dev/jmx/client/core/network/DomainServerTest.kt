package dev.jmx.client.core.network

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
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
        val encrypted = encrypt("""{"Server":["www.a.test","https://www.b.test"]}""")
        val decoder = DomainServerDecoder()

        val result = decoder.decode("\uFEFF$encrypted")

        assertTrue(result is JmxResult.Success)
        assertEquals(listOf("www.a.test", "https://www.b.test"), (result as JmxResult.Success).value.apiHosts)
    }

    @Test
    fun refreshesEndpointManagerFromServer() {
        val encrypted = encrypt("""{"Server":["www.a.test","https://www.b.test"]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(encrypted))
        val manager = ApiEndpointManager(listOf("old.test"))
        val refresher = DomainRefresher(
            endpointManager = manager,
            serverUrls = listOf(server.url("/domains").toString())
        )

        val result = kotlinx.coroutines.runBlocking { refresher.refresh() }

        assertTrue(result is JmxResult.Success)
        assertEquals("https://www.a.test/", manager.all()[0].url.toString())
        assertEquals("https://www.b.test/", manager.all()[1].url.toString())
    }

    private fun encrypt(json: String): String {
        return AesEcbPkcs7.encryptStringToBase64(
            plain = json,
            key = JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret)
        )
    }
}
