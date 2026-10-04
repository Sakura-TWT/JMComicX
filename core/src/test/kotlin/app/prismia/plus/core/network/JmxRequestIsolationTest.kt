package app.prismia.plus.core.network

import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.result.JmxResult
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class JmxRequestIsolationTest {
    @Test fun postOperationsAreNotSilentlyCollapsed() = runBlocking {
        server().use { server ->
            val client = client(server)
            val request = ApiRequest(ApiRoute.FavoriteAction, form = mapOf("aid" to "42"))
            val results = List(2) { async { client.requestJson(request) } }.awaitAll()
            assertTrue(results.all { it is JmxResult.Success })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun differentHeadersAndSuccessPoliciesNeverShareResponses() = runBlocking {
        server().use { server ->
            val client = client(server)
            val requests = listOf(
                ApiRequest(ApiRoute.Setting, headers = mapOf("X-Variant" to "a")),
                ApiRequest(ApiRoute.Setting, headers = mapOf("X-Variant" to "b")),
                ApiRequest(ApiRoute.Setting, headers = mapOf("X-Variant" to "b"), requireSuccessCode = false),
            )
            requests.map { async { client.requestJson(it) } }.awaitAll().forEach { assertTrue(it is JmxResult.Success) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun languageChangeDuringAnInflightRequestDoesNotShareItsOldResponse() = runBlocking {
        server().use { server ->
            val language = AtomicReference("CN")
            val client = client(server, language::get)
            val request = ApiRequest(ApiRoute.Setting)
            val first = async(start = CoroutineStart.UNDISPATCHED) { client.requestJson(request) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            language.set("TW")
            val second = async { client.requestJson(request) }
            assertEquals("CN", first.await().value().asJsonObject["lang"].asString)
            assertEquals("TW", second.await().value().asJsonObject["lang"].asString)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun callersOwnIndependentJsonTreesEvenWhenTheNetworkCallIsShared() = runBlocking {
        server().use { server ->
            val client = client(server)
            val results = List(2) { async { client.requestJson(ApiRequest(ApiRoute.Setting)).value() } }.awaitAll()
            results[0].asJsonObject.addProperty("value", "changed")
            assertEquals("original", results[1].asJsonObject["value"].asString)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellingTheLeaderDoesNotReturnAFabricatedNetworkFailureToAWaiter() = runBlocking {
        server().use { server ->
            val client = client(server)
            val request = ApiRequest(ApiRoute.Setting)
            val leader = async(start = CoroutineStart.UNDISPATCHED) { client.requestJson(request) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { client.requestJson(request) }
            assertEquals(1, client.deduplicatedRequestCount)
            leader.cancelAndJoin()
            assertTrue(withTimeout(5_000) { waiter.await() } is JmxResult.Success)
            assertEquals(2, server.requestCount)
        }
    }

    private fun client(server: MockWebServer, language: () -> String = { "CN" }) = JmxApiClient(
        JmxHttpClient(ApiEndpointManager(listOf(server.url("/").toString())),
            retryPolicy = DefaultRetryPolicy(maxAttempts = 1), queryLanguageProvider = language),
        cacheNamespace = language,
    )

    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val lang = request.requestUrl?.queryParameter("lang") ?: ""
                return MockResponse().setHeadersDelay(300, TimeUnit.MILLISECONDS)
                    .setBody("""{"code":200,"data":{"value":"original","lang":"$lang"}}""")
            }
        }
        start()
    }

    private fun JmxResult<JsonElement>.value(): JsonElement = (this as JmxResult.Success).value
}
