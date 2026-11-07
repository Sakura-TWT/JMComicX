package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.*
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PublicReadInitializationTest {
    private fun response(json: String): MockResponse {
        val cipher = AesEcbPkcs7.encryptStringToBase64(json, JmxHash.md5Hex("1${JmxProtocolConstants.DataSecret}"))
        return MockResponse().setBody("""{"code":200,"data":"$cipher"}""")
    }
    private fun core(api: MockWebServer, domain: MockWebServer): JmxCore = JmxCore.create(JmxCoreConfig(
        keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to api.url("/").toString(), "protocol.api.hosts.generation" to "2026-08")),
        apiClock = object : ApiClock { override fun nowSeconds() = 1L }, retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
        domainServerUrls = listOf(domain.url("/").toString())))

    @Test fun publicContentCompletesWhileMaintenanceDomainResponseIsBlocked() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val api = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response(if (request.requestUrl?.encodedPath == "/setting")
                """{"img_host":"https://img.test","app_shunts":[]}""" else """{"ready":true}""")
        }; start() }
        val domain = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return MockResponse().setResponseCode(503) }
        }; start() }
        try {
            val core = core(api, domain)
            val result = withTimeout(1500) { core.publicRead { core.apiClient.requestJson(apiRequest(ApiRoute.Promote)) } }
            assertTrue(result is JmxResult.Success)
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            assertEquals(1L, release.count)
            release.countDown()
        } finally { release.countDown(); api.shutdown(); domain.shutdown() }
    }

    @Test fun concurrentMaintenanceLaunchesShareOneFlightAndSettingUpdatesImageRegistry() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val api = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return response("""{"img_host":"https://img.test","app_shunts":[]}""")
            }
        }; start() }
        val domain = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)); start() }
        try {
            val core = core(api, domain)
            val first = core.initializer.refreshInBackground()!!
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            repeat(10) { assertSame(first, core.initializer.refreshInBackground()) }
            release.countDown(); first.await()
            assertEquals(1, api.requestCount)
            assertTrue(core.imageHostRegistry.all().any { it.host == "img.test" })
        } finally { release.countDown(); api.shutdown(); domain.shutdown() }
    }
}
