package app.prismia.source.oreno

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OrenoClientTest {
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
