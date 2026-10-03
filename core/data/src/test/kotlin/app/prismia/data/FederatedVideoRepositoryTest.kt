package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.ContentType
import app.prismia.video.PagedVideoCatalog
import app.prismia.video.VideoDetail
import app.prismia.video.VideoPage
import app.prismia.video.VideoWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FederatedVideoRepositoryTest {
    @Test
    fun mergesRecordsWhenOrenoCarriesStableIwaraId() = runBlocking {
        val orenoWork = work(ContentSource.ORENO3D, "o1", "Oreno title", mapOf(ContentSource.ORENO3D to "o1", ContentSource.IWARA to "w1"))
        val iwaraWork = work(ContentSource.IWARA, "w1", "Iwara title", mapOf(ContentSource.IWARA to "w1"))
        val repo = FederatedVideoRepository(FakeCatalog(orenoWork), FakeCatalog(iwaraWork))

        val result = repo.search("title")
        assertEquals(1, result.items.size)
        assertEquals("Oreno title", result.items.single().title)
        assertEquals(setOf(ContentSource.ORENO3D, ContentSource.IWARA), result.items.single().sourceLinks.keys)
    }

    @Test
    fun keepsOneSourceWhenTheOtherFailsAndReportsFailure() = runBlocking {
        val iwaraWork = work(ContentSource.IWARA, "w1", "Iwara title", mapOf(ContentSource.IWARA to "w1"))
        val result = FederatedVideoRepository(
            FailingCatalog(IllegalStateException("oreno unavailable")),
            FakeCatalog(iwaraWork),
        ).searchDetailed("title")

        assertEquals(listOf("w1"), result.items.map { it.key.remoteId })
        assertEquals(ContentSource.ORENO3D, result.failures.single().source)
        assertEquals(SourceErrorCategory.UNKNOWN, result.failures.single().diagnostic.category)
        assertTrue(result.failures.single().error.message!!.contains("unavailable"))
    }

    private fun work(source: ContentSource, id: String, title: String, links: Map<ContentSource, String>) = VideoWork(
        key = ContentKey(ContentType.VIDEO, source, id),
        title = title,
        sourceLinks = links,
    )

    private class FakeCatalog(private val item: VideoWork) : PagedVideoCatalog {
        override suspend fun browse(page: Int, limit: Int) = VideoPage(listOf(item), page, false)
        override suspend fun searchPage(query: String, page: Int, limit: Int) = VideoPage(listOf(item), page, false)
        override suspend fun find(query: String) = listOf(item)
        override suspend fun detail(key: ContentKey) = item
        override suspend fun detailPage(key: ContentKey) = VideoDetail(item)
    }

    private class FailingCatalog(private val failure: Exception) : PagedVideoCatalog {
        override suspend fun browse(page: Int, limit: Int): VideoPage = throw failure
        override suspend fun searchPage(query: String, page: Int, limit: Int): VideoPage = throw failure
        override suspend fun find(query: String): List<VideoWork> = throw failure
        override suspend fun detail(key: ContentKey): VideoWork = throw failure
        override suspend fun detailPage(key: ContentKey): VideoDetail = throw failure
    }
}
