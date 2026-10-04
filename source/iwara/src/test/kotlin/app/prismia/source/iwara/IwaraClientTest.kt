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
    fun removedVideoNeverResolvesItsFileUrl() = runBlocking {
        var requests = 0
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String {
                requests++
                assertEquals("video/v1", path)
                return """{"id":"v1","status":"deleted","fileUrl":"https://cdn.example.test/file"}"""
            }
        }
        val detail = IwaraClient(transport).detailPage(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1"))
        assertEquals(app.prismia.video.VideoAvailability.TOMBSTONED, detail.work.availability)
        assertTrue(detail.variants.isEmpty())
        assertEquals(1, requests)
    }

    @Test
    fun oversizedDurationCannotWrapIntoASmallValidValue() = runBlocking {
        val client = IwaraClient(jsonTransport("""{"results":[{"id":"v1","duration":18446744073709551617}]}"""))
        assertEquals(null, client.browse(0, 1).items.single().durationMs)
    }

    @Test
    fun integerThumbnailIsAnIndexAndNeverAnImageUrl() = runBlocking {
        val client = IwaraClient(jsonTransport("""{"results":[{"id":"v1","thumbnail":3,"file":{"id":"file-id","numThumbnails":8}}]}"""))
        assertEquals("https://i.iwara.tv/image/thumbnail/file-id/thumbnail-03.jpg", client.browse(0, 1).items.single().coverUrl)
    }

    @Test
    fun missingResultsOrBrokenRecordsAreNotSuccessfulEmptyPages() = runBlocking {
        for (body in listOf("{}", "{\"results\":[null]}", "{\"results\":[{\"id\":\"../bad\"}]}")) {
            assertTrue(runCatching { IwaraClient(jsonTransport(body)).browse(0, 1) }.exceptionOrNull() is IwaraParseException)
        }
        assertTrue(IwaraClient(jsonTransport("{\"results\":[]}")).browse(0, 1).items.isEmpty())
    }

    @Test
    fun detailIdMustMatchTheRequestedContent() = runBlocking {
        val key = ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1")
        val failure = runCatching { IwaraClient(jsonTransport("{\"id\":\"v2\"}")).detailPage(key) }.exceptionOrNull()
        assertTrue(failure is IwaraParseException)
    }

    @Test
    fun missingExpiryDoesNotInventALongLivedSignedUrlLease() = runBlocking {
        val body = """{"id":"v1","renditions":[{"name":"720","src":{"view":"https://cdn.example.test/file"}}]}"""
        val detail = IwaraClient(jsonTransport(body)).detailPage(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1"))
        assertEquals(null, detail.variants.single().expiresAtEpochSeconds)
    }

    private fun jsonTransport(body: String) = object : IwaraTransport {
        override suspend fun get(path: String, query: Map<String, String>) = body
    }

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
