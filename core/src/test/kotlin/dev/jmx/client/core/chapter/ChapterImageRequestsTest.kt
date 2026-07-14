package dev.jmx.client.core.chapter

import dev.jmx.client.core.download.DownloadEvent
import dev.jmx.client.core.download.DownloadObserver
import dev.jmx.client.core.download.ImageHttpHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterImageRequestsTest {
    @Test
    fun defaultHeadersUseMobileImageProfile() {
        val template = ChapterTemplate(
            albumId = 300000,
            scrambleId = 1,
            speed = "0",
            imageHost = "https://img.test",
            chapterId = "123456",
            cacheSuffix = "?v=1",
            imageFileNames = listOf("00001.webp")
        )

        val request = template.toImageDownloadRequests().single()
        val expected = ImageHttpHeaders.default(refererHost = "https://img.test")

        assertEquals(expected, request.headers)
        assertTrue(request.headers.containsKey("X-Requested-With"))
        assertTrue(!request.headers.containsKey("Accept-Encoding"))
    }

    @Test
    fun mapsTemplateImagesToRestoreRequests() {
        val observers = mutableListOf<DownloadObserver>()
        val template = ChapterTemplate(
            albumId = 300000,
            scrambleId = 1,
            speed = "0",
            imageHost = "https://img.test/",
            chapterId = "123456",
            cacheSuffix = "?v=1",
            imageFileNames = listOf("00001.webp", "00002.jpg")
        )

        val requests = template.toImageDownloadRequests(
            headers = mapOf("referer" to "https://jm.test"),
            maxBytes = 1024,
            observerFactory = { index, url ->
                DownloadObserver { event ->
                    assertEquals(url, event.url)
                    assertEquals(index, requestsIndexFromUrl(url))
                }.also { observers += it }
            }
        )

        assertEquals(2, requests.size)
        assertEquals("https://img.test/media/photos/123456/00001.webp?v=1", requests[0].sourceUrl)
        assertEquals("https://img.test/media/photos/123456/00002.jpg?v=1", requests[1].sourceUrl)
        assertEquals(300000, requests[0].albumId)
        assertEquals(1, requests[0].scrambleId)
        assertEquals(mapOf("referer" to "https://jm.test"), requests[0].headers)
        assertEquals(setOf("image/*"), requests[0].acceptedContentTypes)
        assertEquals(1024L, requests[0].maxBytes)
        assertSame(observers[0], requests[0].observer)
        assertSame(observers[1], requests[1].observer)
        requests[0].observer.onEvent(DownloadEvent.Started(requests[0].sourceUrl))
    }

    private fun requestsIndexFromUrl(url: String): Int {
        return when {
            url.contains("00001") -> 0
            url.contains("00002") -> 1
            else -> -1
        }
    }
}
