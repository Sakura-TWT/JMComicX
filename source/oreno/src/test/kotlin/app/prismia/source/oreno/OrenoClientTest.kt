package app.prismia.source.oreno

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrenoClientTest {
    @Test
    fun invalidDetailIdIsRejectedBeforeTransportIsInvoked() = runBlocking {
        val client = OrenoClient(object : OrenoTransport {
            override suspend fun get(path: String, query: Map<String, String>): String = error("unexpected request")
        })
        for (id in listOf("../42", "42?query=value", "x42", "1".repeat(21))) {
            val key = ContentKey(ContentType.VIDEO, ContentSource.ORENO3D, id)
            assertTrue(runCatching { client.detailPage(key) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun mapsZeroBasedDomainPageToOrenoOneBasedQuery() = runBlocking {
        var requestedQuery: Map<String, String> = emptyMap()
        val transport = object : OrenoTransport {
            override suspend fun get(path: String, query: Map<String, String>): String {
                requestedQuery = query
                return """
                    <article class="movie-card">
                      <a href="/movies/42" title="Sample"><img src="/thumb.jpg"></a>
                    </article>
                """.trimIndent()
            }
        }

        val page = OrenoClient(transport).browse(page = 0, limit = 36)

        assertEquals("1", requestedQuery["page"])
        assertEquals("42", page.items.single().key.remoteId)
    }
}
