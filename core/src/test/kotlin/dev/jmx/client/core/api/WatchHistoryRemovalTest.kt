package dev.jmx.client.core.api

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.*
import dev.jmx.client.core.protocol.*
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WatchHistoryRemovalTest {
    @Test fun singleDeleteAcceptsOnlyNumericAlbumIds() {
        assertEquals("123456", watchHistoryDeleteIdOrNull(" 123456 "))
        assertNull(watchHistoryDeleteIdOrNull(""))
        assertNull(watchHistoryDeleteIdOrNull("all"))
        assertNull(watchHistoryDeleteIdOrNull("12a"))
    }

    @Test fun removalRequiresExplicitStatusAndRejectsExplicitFailure() {
        assertFalse(ActionResult(null, null, null, emptyMap()).acceptsHistoryRemoval())
        assertTrue(ActionResult("OK", null, null, emptyMap()).acceptsHistoryRemoval())
        assertTrue(ActionResult("success", null, null, emptyMap()).acceptsHistoryRemoval())
        assertFalse(ActionResult("fail", "no", null, emptyMap()).acceptsHistoryRemoval())
    }

    @Test fun deletePostsNumericIdAndDoesNotClaimEmptyPayloadSuccess() = runBlocking {
        withServer { server, api ->
            server.enqueue(response("{\"status\":\"ok\"}"))
            assertTrue(api.deleteWatchHistory(" 123 ") is JmxResult.Success)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/watch_list", request.path)
            assertEquals("id=123", request.body.readUtf8())
            server.enqueue(MockResponse().setBody("{\"code\":200,\"data\":null}"))
            val empty = api.deleteWatchHistory("123")
            assertTrue(empty is JmxResult.Failure && empty.error is JmxError.EmptyData)
        }
    }

    @Test fun unknownWriteShapeRequiresReadBackAndExplicitFailureStaysFailure() = runBlocking {
        withServer { server, api ->
            for (body in listOf("{}", "[]", "{\"msg\":\"unknown\"}")) {
                server.enqueue(response(body))
                val result = api.deleteWatchHistory("123")
                assertTrue(result is JmxResult.Failure && result.error is JmxError.EmptyData)
            }
            server.enqueue(response("{\"status\":\"fail\",\"msg\":\"synthetic rejection\"}"))
            val failed = api.deleteWatchHistory("123")
            assertTrue(failed is JmxResult.Failure && failed.error is JmxError.Schema)
            server.enqueue(MockResponse().setResponseCode(401))
            val unauthorized = api.deleteWatchHistory("123")
            assertTrue(unauthorized is JmxResult.Failure && unauthorized.error is JmxError.Http)
        }
    }

    @Test fun freshEmptyReadRequiresAnActualListAndCountIsNotTotal() = runBlocking {
        withServer { server, api ->
            for (body in listOf("{}", "[null]", "[{\"name\":\"missing id\"}]", "{\"content\":null}")) {
                server.enqueue(response(body))
                assertTrue(api.watchList(1) is JmxResult.Failure)
            }
            server.enqueue(response("[]"))
            assertTrue(api.watchList(1) is JmxResult.Success)
            server.enqueue(response("{\"count\":20,\"content\":[{\"id\":\"123\"}]}"))
            val page = api.watchList(1)
            assertTrue(page is JmxResult.Success)
            assertNull((page as JmxResult.Success).value.total)
        }
    }

    private suspend fun withServer(block: suspend (MockWebServer, LibraryApi) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val tokenProvider = ApiTokenProvider(clock = object : ApiClock { override fun nowSeconds() = TS }, versionProvider = { JmxProtocolConstants.DefaultApiVersion })
            val client = JmxApiClient(JmxHttpClient(ApiEndpointManager(listOf(server.url("/").toString())), tokenProvider, retryPolicy = DefaultRetryPolicy(maxAttempts = 1)))
            block(server, LibraryApi(client))
        } finally { server.shutdown() }
    }

    private fun response(body: String): MockResponse {
        val data = AesEcbPkcs7.encryptStringToBase64(body, JmxHash.md5Hex("$TS${JmxProtocolConstants.DataSecret}"))
        return MockResponse().setBody("{\"code\":200,\"data\":\"$data\"}")
    }

    private companion object { const val TS = 1700566805L }
}
