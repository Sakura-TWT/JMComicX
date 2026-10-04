package app.prismia.plus.core.network

import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JmTransportLifecycleTest {
    @Test fun cancellingBodyReadStopsJmRequestWithoutPenalizingEndpoint() = runBlocking {
        assertBodyCancellation(probe = false)
    }

    @Test fun cancellingProbeBodyReadDoesNotMarkTheEndpointUnhealthy() = runBlocking {
        assertBodyCancellation(probe = true)
    }

    private suspend fun assertBodyCancellation(probe: Boolean) = kotlinx.coroutines.coroutineScope {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("a".repeat(128)).throttleBody(1, 1, TimeUnit.SECONDS))
            val reading = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { reading.complete(Unit) }
                override fun callFailed(call: Call, ioe: IOException) { stopped.complete(Unit) }
            }).build()
            val endpoints = ApiEndpointManager(listOf(server.url("/").toString()))
            val pending = async(Dispatchers.IO) {
                if (probe) ApiEndpointProber(endpoints, okHttpClient = client).probe(server.url("/"))
                else JmxHttpClient(endpoints, okHttpClient = client).execute(ApiRequest(ApiRoute.ChapterViewTemplate))
            }
            withTimeout(5_000) { reading.await() }
            withTimeout(2_000) { pending.cancelAndJoin(); stopped.await() }
            val endpoint = endpoints.all().single()
            assertEquals(0, endpoint.successCount)
            assertEquals(0, endpoint.failureCount)
        }
    }

    @Test fun domainRaceCancelsAStreamingLoserBeforeReturningTheWinner() = runBlocking {
        MockWebServer().use { slow -> MockWebServer().use { fast ->
            slow.start(); fast.start()
            val started = CountDownLatch(1)
            val stopped = CompletableDeferred<Unit>()
            slow.enqueue(MockResponse().setBody("a".repeat(128)).throttleBody(1, 1, TimeUnit.SECONDS))
            fast.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(started.await(5, TimeUnit.SECONDS))
                    val encrypted = AesEcbPkcs7.encryptStringToBase64("""{"Server":["healthy.test"]}""",
                        JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret))
                    return MockResponse().setBody(encrypted)
                }
            }
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { if (call.request().url.port == slow.port) started.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { if (call.request().url.port == slow.port) stopped.complete(Unit) }
            }).build()
            val manager = ApiEndpointManager(listOf("old.test"))
            val refresher = DomainRefresher(manager, client, serverUrls = listOf(slow.url("/").toString(), fast.url("/").toString()))
            val result = withTimeout(5_000) { refresher.refresh() }
            assertTrue(result is JmxResult.Success)
            assertEquals("healthy.test", manager.all().single().url.host)
            withTimeout(2_000) { stopped.await() }
        } }
    }

    @Test fun oversizedApiResponseIsNotRetriedAsANetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("a".repeat(64)))
            val client = JmxHttpClient(ApiEndpointManager(listOf(server.url("/").toString())), maxResponseBytes = 16)
            val result = client.execute(ApiRequest(ApiRoute.ChapterViewTemplate))
            assertTrue(result is JmxResult.Failure && result.error is JmxError.Schema && !result.error.retryable)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retryAfterSecondsCannotOverflowIntoANegativeDelay() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", Long.MAX_VALUE.toString()).setBody("busy"))
            val client = JmxHttpClient(ApiEndpointManager(listOf(server.url("/").toString())), retryPolicy = DefaultRetryPolicy(maxAttempts = 1))
            val failure = client.execute(ApiRequest(ApiRoute.ChapterViewTemplate)) as JmxResult.Failure
            assertEquals(Long.MAX_VALUE, (failure.error as JmxError.Http).retryAfterMillis)
        }
    }
}
