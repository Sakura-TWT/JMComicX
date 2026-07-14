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

class JmxLiveLoginRunnerTest {
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
    fun missingCredentialsSkipsWithoutFailingHard() {
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                domainServerUrls = emptyList()
            )
        )

        val runner = JmxLiveLoginRunner(core, JmxLiveLoginCredentialsResolver { null })

        val report = kotlinx.coroutines.runBlocking {
            runner.run(
                JmxLiveLoginScenario(
                    username = null,
                    password = null,
                    credentialsFile = java.nio.file.Path.of("definitely-missing-jmx-creds.properties")
                )
            )
        }

        assertFalse(report.acceptance.credentialsPresent)
        assertFalse(report.acceptance.meetsMinimum)
        assertTrue(report.login.isSkipped)
    }

    @Test
    fun loginInstallsAvsAndProbesAuthenticatedLists() {
        val ts = 1700566805L

        server.enqueue(encrypted(ts, """{"jm3_version":"2.0.27","img_host":"https://img.test","app_shunts":[]}"""))

        server.enqueue(
            encrypted(ts, """{"s":"avs-live","uid":"77","username":"alice","album_favorites":1,"album_favorites_max":40}""")
                .addHeader("Set-Cookie", "SESSION=temp; Path=/")
        )

        server.enqueue(encrypted(ts, """{"total":1,"list":[{"id":"1","name":"fav"}]}"""))

        server.enqueue(encrypted(ts, """{"total":0,"list":[]}"""))

        server.enqueue(encrypted(ts, """{"daily_id":3,"event_name":"checkin","record":[]}"""))

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
            core.loginRunner.run(
                JmxLiveLoginScenario(
                    username = "user",
                    password = "pass",
                    initialize = true
                )
            )
        }

        assertTrue(report.isSuccessful)
        assertTrue(report.acceptance.meetsMinimum)
        assertEquals("argument", report.credentialsSource)
        assertEquals("avs-live", report.login.valueOrNull()!!.avs)
        assertTrue(report.avsCheck.valueOrNull() == true)
        assertEquals(1, report.favorite.valueOrNull()!!.content.size)
        assertEquals(3, report.daily.valueOrNull()!!.dailyId)
        assertTrue(
            core.sessionManager.cookies().any { it.name.equals("AVS", ignoreCase = true) }
        )
    }

    private fun encrypted(ts: Long, json: String): MockResponse {
        val body = AesEcbPkcs7.encryptStringToBase64(
            plain = json,
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$body"}""")
    }

    private fun fixedClock(ts: Long): ApiClock = object : ApiClock {
        override fun nowSeconds(): Long = ts
    }
}
