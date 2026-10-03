package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.PagedVideoCatalog
import app.prismia.video.StreamVariant
import app.prismia.video.VideoDetail
import app.prismia.video.VideoPage
import app.prismia.video.VideoWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackCoordinatorTest {
    private val key = ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1")

    @Test
    fun usableCachedVariantAvoidsNetworkRefresh() = runBlocking {
        val catalog = CountingCatalog(StreamVariant("720", "https://cdn/720", expiresAtEpochSeconds = 2_000))
        val result = VideoPlaybackCoordinator(catalog, nowEpochSeconds = { 1_000 })
            .resolve(key, cachedVariant = catalog.variant)

        assertFalse(result.refreshed)
        assertEquals(0, catalog.detailCalls)
    }

    @Test
    fun expiredVariantRefreshesAndHonorsPreferredQuality() = runBlocking {
        val catalog = CountingCatalog(
            StreamVariant("720", "https://cdn/720", expiresAtEpochSeconds = 1_000),
            StreamVariant("1080", "https://cdn/1080", expiresAtEpochSeconds = 2_000),
        )
        val result = VideoPlaybackCoordinator(catalog, nowEpochSeconds = { 1_000 })
            .resolve(key, cachedVariant = catalog.variant, preferredName = "1080")

        assertTrue(result.refreshed)
        assertEquals("1080", result.variant.name)
        assertEquals(1, catalog.detailCalls)
    }

    private class CountingCatalog(vararg variants: StreamVariant) : PagedVideoCatalog {
        val variant = variants.first()
        private val refreshed = variants.toList()
        var detailCalls = 0

        override suspend fun browse(page: Int, limit: Int) = VideoPage(emptyList(), page, false)
        override suspend fun searchPage(query: String, page: Int, limit: Int) = VideoPage(emptyList(), page, false)
        override suspend fun find(query: String) = emptyList<VideoWork>()
        override suspend fun detail(key: ContentKey) = VideoWork(key, "work")
        override suspend fun detailPage(key: ContentKey): VideoDetail {
            detailCalls++
            return VideoDetail(VideoWork(key, "work"), refreshed)
        }
    }
}
