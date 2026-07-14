package dev.jmx.client.core.network

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.JmxProtocolConstants
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiEndpointProberTest {
    private lateinit var failingServer: MockWebServer
    private lateinit var healthyServer: MockWebServer

    @Before
    fun setUp() {
        failingServer = MockWebServer()
        failingServer.start()
        healthyServer = MockWebServer()
        healthyServer.start()
    }

    @After
    fun tearDown() {
        failingServer.shutdown()
        healthyServer.shutdown()
    }

    @Test
    fun probeAllUpdatesEndpointHealthForSuccessAndFailure() {
        failingServer.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        healthyServer.enqueue(encryptedSettingResponse())
        val endpointManager = ApiEndpointManager(
            initialHosts = listOf(failingServer.url("/").toString(), healthyServer.url("/").toString())
        )
        val prober = ApiEndpointProber(
            endpointManager = endpointManager,
            tokenProvider = fixedTokenProvider()
        )

        val results = kotlinx.coroutines.runBlocking { prober.probeAll() }

        assertEquals(2, results.size)
        assertTrue(!results[0].success)
        assertEquals(503, results[0].statusCode)
        assertTrue(results[0].error != null)
        assertTrue(results[1].success)
        assertEquals(200, results[1].statusCode)
        val endpoints = endpointManager.all()
        assertEquals(1, endpoints[0].failureCount)
        assertEquals(1, endpoints[0].consecutiveFailureCount)
        assertEquals(1, endpoints[1].successCount)
        assertTrue(endpoints[1].lastLatencyMillis != null)
        assertEquals("/setting", failingServer.takeRequest().path)
        assertEquals("/setting", healthyServer.takeRequest().path)
    }

    @Test
    fun probeTreatsApiCodeFailureAsEndpointFailure() {
        healthyServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":403,"msg":"denied"}"""))
        val endpointManager = ApiEndpointManager(initialHosts = listOf(healthyServer.url("/").toString()))
        val prober = ApiEndpointProber(
            endpointManager = endpointManager,
            tokenProvider = fixedTokenProvider()
        )

        val result = kotlinx.coroutines.runBlocking { prober.probe(healthyServer.url("/")) }

        assertTrue(!result.success)
        assertEquals(403, result.error!!.let { (it as dev.jmx.client.core.result.JmxError.Api).code })
        assertEquals(1, endpointManager.all().single().failureCount)
    }

    private fun encryptedSettingResponse(): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            plain = """{"jm3_version":"2.0.26","img_host":"https://img.test","app_shunts":[]}""",
            key = JmxHash.md5Hex("$TS${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private fun fixedTokenProvider(): ApiTokenProvider {
        return ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = TS
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
    }

    private companion object {
        const val TS = 1700566805L
    }
}
