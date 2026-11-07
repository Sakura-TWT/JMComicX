package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.protocol.*
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PublicReadRecoveryTest {
    private fun response(version: String? = null): MockResponse {
        val json = if (version == null) """{"img_host":"https://img.test","app_shunts":[]}"""
            else """{"jm3_version":"$version","img_host":"https://img.test","app_shunts":[]}"""
        val cipher = AesEcbPkcs7.encryptStringToBase64(json, JmxHash.md5Hex("1${JmxProtocolConstants.DataSecret}"))
        return MockResponse().setBody("""{"code":200,"data":"$cipher"}""")
    }
    private fun core(api: MockWebServer, domain: MockWebServer) = JmxCore.create(JmxCoreConfig(
        keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to api.url("/").toString(), "protocol.api.hosts.generation" to "2026-08")),
        apiClock = object : ApiClock { override fun nowSeconds() = 1L }, retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
        domainServerUrls = listOf(domain.url("/").toString())))

    @Test fun unchangedConfigurationNeverRepeatsFailedContentRead() = runBlocking<Unit> {
        val api = MockWebServer().apply { enqueue(response()); start() }
        val domain = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)); start() }
        try {
            val core = core(api, domain); var calls = 0
            val failure = JmxResult.Failure(JmxError.Network("fixture"))
            assertSame(failure, withTimeout(2000) { core.publicRead<Unit> { calls++; failure } })
            assertEquals(1, calls); core.initializer.refreshInBackground()!!.await()
        } finally { api.shutdown(); domain.shutdown() }
    }
    @Test fun changedProtocolAllowsExactlyOneRecoveryRead() = runBlocking<Unit> {
        val api = MockWebServer().apply { enqueue(response("9.9.9")); start() }
        val domain = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)); start() }
        try {
            val core = core(api, domain); var calls = 0
            val result = withTimeout(2000) { core.publicRead { if (++calls == 1) JmxResult.Failure(JmxError.Decode("fixture")) else JmxResult.Success(Unit) } }
            assertTrue(result is JmxResult.Success); assertEquals(2, calls)
            assertEquals("9.9.9", core.apiVersionProvider.current())
        } finally { api.shutdown(); domain.shutdown() }
    }
    @Test fun warmDomainChangeIsAwaitedBeforeSingleRecoveryRead() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val recovered = MockWebServer().apply { start() }
        val api = MockWebServer().apply { enqueue(response()); start() }
        val domain = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                val json = """{"Server":["${recovered.url("/")}"]}"""
                return MockResponse().setBody(AesEcbPkcs7.encryptStringToBase64(json, JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret)))
            }
        }; start() }
        try {
            val core = core(api, domain); var calls = 0
            core.protocolStateStore.updatePreferredAutoApiHost(api.url("/").toString())
            assertNotNull(core.protocolStateStore.preferredAutoApiHost())
            val firstReady = CompletableDeferred<Unit>()
            val read = async {
                core.publicRead {
                    if (++calls == 1) {
                        withTimeout(2000) { while (core.imageHostRegistry.all().none { it.host == "img.test" }) delay(1) }
                        firstReady.complete(Unit)
                        JmxResult.Failure(JmxError.Network("fixture"))
                    } else JmxResult.Success(Unit)
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            withTimeout(2000) { firstReady.await() }
            assertNull("a setting-only flight is not the end of warm bootstrap", withTimeoutOrNull(250) { read.await() })
            release.countDown()
            assertTrue(withTimeout(2000) { read.await() } is JmxResult.Success); assertEquals(2, calls)
            assertTrue(core.endpointManager.all().any { it.url.toString().startsWith(recovered.url("/").toString().trimEnd('/')) })
        } finally { release.countDown(); api.shutdown(); domain.shutdown(); recovered.shutdown() }
    }
    @Test fun cancellingContentSubscriberDoesNotCancelSharedMaintenance() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val api = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return response() }
        }; start() }
        val domain = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)); start() }
        try {
            val core = core(api, domain)
            val caller = async { core.publicRead<Unit> { awaitCancellation() } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            val maintenance = core.initializer.refreshInBackground()!!
            caller.cancelAndJoin(); assertFalse(maintenance.isCancelled)
            release.countDown(); withTimeout(2000) { maintenance.await() }
            assertEquals(1, api.requestCount)
        } finally { release.countDown(); api.shutdown(); domain.shutdown() }
    }
    @Test fun schemaFailureDoesNotWaitForMaintenanceOrRepeatBusinessRead() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val api = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { check(release.await(5, TimeUnit.SECONDS)); return response("9.9.9") }
        }; start() }
        val domain = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)); start() }
        try {
            val core = core(api, domain); var calls = 0
            val failure = JmxResult.Failure(JmxError.Schema("fixture"))
            assertSame(failure, withTimeout(1000) { core.publicRead<Unit> { calls++; failure } }); assertEquals(1, calls)
            release.countDown(); core.initializer.refreshInBackground()!!.await()
        } finally { release.countDown(); api.shutdown(); domain.shutdown() }
    }
}
