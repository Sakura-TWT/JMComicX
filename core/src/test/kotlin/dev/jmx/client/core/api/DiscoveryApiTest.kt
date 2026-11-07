package dev.jmx.client.core.api

import com.google.gson.JsonParser
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.*
import dev.jmx.client.core.protocol.*
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiscoveryApiTest {
    @Test fun missingOrMalformedListsNeverBecomeSuccessfulEmptyPages() {
        listOf("{}", "null", "false", "{\"content\":{}}", "[false]", "[{\"name\":\"x\"}]", "[{\"id\":true}]", "[{\"id\":\"han-1\"}]").forEach {
            assertTrue(it, JsonParser.parseString(it).discoveryPage() is JmxResult.Failure)
        }
    }
    @Test fun explicitEmptyArraysAreAccepted() {
        listOf("[]", "{\"content\":[],\"total\":0}").forEach {
            val result = JsonParser.parseString(it).discoveryPage()
            assertTrue(result is JmxResult.Success)
            assertTrue((result as JmxResult.Success).value.content.isEmpty())
        }
    }
    @Test fun pageCountIsNotTotalAndBadTotalsAreRejected() {
        val result = JsonParser.parseString("""{"count":1,"content":[{"id":"123","name":"x"}]}""").discoveryPage() as JmxResult.Success
        assertNull(result.value.total)
        for (total in listOf("-1", "0", "1.5", "true", "{}", "\"unknown\"")) {
            assertTrue(JsonParser.parseString("""{"total":$total,"content":[{"id":"123"}]}""").discoveryPage() is JmxResult.Failure)
        }
    }
    @Test fun weekMetadataRequiresExplicitArraysAndValidIds() {
        listOf("{}", "{\"category\":[]}", "{\"category\":{},\"type\":[]}", "{\"category\":[{\"id\":true}],\"type\":[]}", "{\"category\":[],\"type\":[{}]}").forEach {
            assertTrue(JsonParser.parseString(it).asJsonObject.discoveryWeekInfo() is JmxResult.Failure)
        }
    }
    @Test fun weekMetadataPreservesServerOrderDeduplicatesIdsAndAcceptsExplicitEmpty() {
        val data = JsonParser.parseString("""{"category":[{"id":"edition 23","title":"最新"},{"id":"22"},{"id":"22"}],"type":[{"id":2,"title":"分类二"},{"id":"1"}]}""").asJsonObject.discoveryWeekInfo() as JmxResult.Success
        assertEquals(listOf("edition 23", "22"), data.value.categories.map { it.id })
        assertEquals(listOf("2", "1"), data.value.types.map { it.id })
        val empty = JsonParser.parseString("""{"categories":[],"category":[{"id":"9"}],"type":[]}""").asJsonObject.discoveryWeekInfo() as JmxResult.Success
        assertTrue(empty.value.categories.isEmpty())
        assertTrue(empty.value.types.isEmpty())
    }
    @Test fun weekFilterUsesExactServerIdsAndOneBasedPageWithoutWriting() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = LibraryApi(client(server))
            server.enqueue(response("""{"content":[{"id":"123"}],"total":1}"""))
            assertTrue(api.weekFilter(0, "edition 23", "2", forceRefresh = true) is JmxResult.Success)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/week/filter", request.requestUrl!!.encodedPath)
            assertEquals("edition 23", request.requestUrl!!.queryParameter("id"))
            assertEquals("2", request.requestUrl!!.queryParameter("type"))
            assertEquals("1", request.requestUrl!!.queryParameter("page"))
        } finally { server.shutdown() }
    }
    @Test fun missingWeekFilterSelectionFailsBeforeNetwork() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = LibraryApi(client(server))
            assertTrue(api.weekFilter(1, "", "2") is JmxResult.Failure)
            assertTrue(api.weekFilter(1, "23", " ") is JmxResult.Failure)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun allFourRankPeriodsAndCanonicalCategoriesUseTheOfficialFilterContract() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = LibraryApi(client(server))
            val categories = listOf("0", "doujin", "single", "short", "hanman", "meiman", "another", "doujin_cosplay", "3D", "english_site")
            for ((time, expected) in listOf("t" to "mv_t", "w" to "mv_w", "m" to "mv_m", "a" to "mv")) for (category in categories) {
                server.enqueue(response("""{"content":[{"id":"123"}],"total":1}"""))
                assertTrue(api.categoriesFilter(CategoryFilter(1, time, category, order = JmxMagicConstants.ORDER_BY_VIEW), forceRefresh = true) is JmxResult.Success)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/categories/filter", request.requestUrl!!.encodedPath)
                assertEquals(category, request.requestUrl!!.queryParameter("c"))
                assertEquals("", request.requestUrl!!.queryParameter("order"))
                assertEquals(expected, request.requestUrl!!.queryParameter("o"))
            }
            assertEquals(40, server.requestCount)
        } finally { server.shutdown() }
    }
    private fun client(server: MockWebServer) = JmxApiClient(JmxHttpClient(
        endpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
        tokenProvider = ApiTokenProvider(clock = object : ApiClock { override fun nowSeconds() = TS }, versionProvider = { JmxProtocolConstants.DefaultApiVersion }),
        retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
    ))
    private fun response(json: String): MockResponse {
        val encoded = AesEcbPkcs7.encryptStringToBase64(json, JmxHash.md5Hex("$TS${JmxProtocolConstants.DataSecret}"))
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encoded"}""")
    }
    private companion object { const val TS = 1700566805L }
}
