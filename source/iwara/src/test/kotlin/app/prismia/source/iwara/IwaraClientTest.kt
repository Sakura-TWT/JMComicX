package app.prismia.source.iwara

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IwaraClientTest {
    @Test
    fun parsesPagedResultsAndUsesVideoSearchType() = runBlocking {
        var requestedPath = ""
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String {
                requestedPath = "$path?${query.entries.joinToString("&") { "${it.key}=${it.value}" }}"
                return """{"results":[{"id":"v1","title":"Clip","user":{"username":"author"}}]}"""
            }
        }

        val page = IwaraClient(transport).searchPage("clip", page = 0, limit = 32)
        assertEquals("v1", page.items.single().key.remoteId)
        assertEquals("author", page.items.single().author)
        assertTrue(requestedPath.startsWith("search?"))
        assertTrue(requestedPath.contains("type=videos"))
    }

    @Test
    fun resolvesSignedFileUrlAndNormalizesProtocolRelativeRendition() = runBlocking {
        val requests = mutableListOf<String>()
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String {
                requests += path
                return if (path.startsWith("https://filesq.iwara.tv")) {
                    """[{"name":"720","src":{"view":"//edge.iwara.tv/view?expires=1700000123&hash=x"}}]"""
                } else {
                    """{"id":"v1","title":"Clip","fileUrl":"https://filesq.iwara.tv/file/f1?expires=1700000999000&hash=x"}"""
                }
            }
        }

        val detail = IwaraClient(transport) { 1_700_000_000L }
            .detailPage(ContentKey(
                ContentType.VIDEO,
                ContentSource.IWARA,
                "v1",
            ))

        assertEquals("https://edge.iwara.tv/view?expires=1700000123&hash=x", detail.variants.single().url)
        assertEquals(1_700_000_123L, detail.variants.single().expiresAtEpochSeconds)
        assertEquals(2, requests.size)
    }

    @Test
    fun parsesArchivedApiShapeWithNestedFileMetadata() = runBlocking {
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String =
                """{"count":1,"page":0,"limit":1,"results":[{"id":"lJ86OCf2wtcYeb","title":"Clip","file":{"duration":199},"user":{"name":"author"},"tags":[{"id":"koikatsu"}]}]}"""
        }

        val work = IwaraClient(transport).browse(page = 0, limit = 1).items.single()

        assertEquals("lJ86OCf2wtcYeb", work.key.remoteId)
        assertEquals(199_000L, work.durationMs)
        assertEquals(listOf("koikatsu"), work.tags)
    }
}
