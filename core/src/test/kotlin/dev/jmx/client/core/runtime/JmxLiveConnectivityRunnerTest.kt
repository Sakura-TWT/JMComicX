package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.JmxProtocolConstants
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JmxLiveConnectivityRunnerTest {
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
    fun connectivityRunnerPassesMinimumGatesOnMockStack() {
        val ts = 1700566805L
        val imageHost = "https://img.test"

        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.8.0","img_host":"$imageHost","app_shunts":[{"id":"1","title":"线路1"}]}"""))

        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.8.0","img_host":"$imageHost","app_shunts":[{"id":"1","title":"线路1"}]}"""))

        server.enqueue(
            encryptedResponse(
                ts,
                """{"id":"438516","name":"demo album","author":["a"],"series":[{"id":"438516","name":"第1话","sort":"1"}],"tags":["t"]}"""
            )
        )

        server.enqueue(
            encryptedResponse(
                ts,
                """{"total":1,"content":[{"id":"1","name":"hit","author":"x"}]}"""
            )
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(chapterHtml(imageHost))
        )

        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = emptyList()
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.connectivityRunner.run(
                JmxLiveConnectivityScenario(
                    refreshDomains = false,
                    probeEndpoints = true,
                    fetchSetting = true,
                    albumId = "438516",
                    searchQuery = "无修正",
                    chapterId = "438516",
                    shunt = "1"
                )
            )
        }

        assertTrue(report.isSuccessful)
        assertTrue(report.acceptance.meetsMinimum)
        assertTrue(report.acceptance.hasUsableEndpoint)
        assertTrue(report.acceptance.settingOk)
        assertTrue(report.acceptance.albumOk)
        assertTrue(report.acceptance.searchOk)
        assertTrue(report.acceptance.chapterTemplateOk)
        assertEquals("438516", report.albumDetail.valueOrNull()!!.id)
        assertEquals(1, report.search.valueOrNull()!!.content.size)
        assertEquals(1, report.chapterTemplate.valueOrNull()!!.imageFileNames.size)
        assertEquals(1, report.endpointProbe.valueOrNull()!!.successCount)

        val markdown = JmxLiveConnectivityMarkdownRenderer().render(report)
        assertTrue(markdown.contains("Meets minimum: `true`"))
        assertTrue(markdown.contains("album detail"))
    }

    @Test
    fun connectivityRunnerReportsFailuresWithoutCrashing() {
        val ts = 1700566805L

        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = emptyList()
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.connectivityRunner.run(
                JmxLiveConnectivityScenario(refreshDomains = false)
            )
        }

        assertFalse(report.isSuccessful)
        assertFalse(report.acceptance.meetsMinimum)
        assertFalse(report.acceptance.albumOk)
        assertTrue(report.failedSteps.isNotEmpty())
        assertTrue(report.issues.any { it.severity != JmxDiagnosticSeverity.Info })
    }

    private fun encryptedResponse(ts: Long, dataJson: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            plain = dataJson,
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse()
            .setResponseCode(200)
            .setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private fun fixedClock(ts: Long): ApiClock {
        return object : ApiClock {
            override fun nowSeconds(): Long = ts
        }
    }

    private fun chapterHtml(imageHost: String): String {
        return """
            <html><script>
            const result = {"images":["00001.webp"]};
            const config = {"imghost":"$imageHost","jmid":"438516","cache":"?v=1"};
            const aid = 438516;
            const scramble_id = 220980;
            const speed = "0";
            </script></html>
        """.trimIndent()
    }
}
