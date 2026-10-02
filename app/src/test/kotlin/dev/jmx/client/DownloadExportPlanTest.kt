package dev.jmx.client

import dev.jmx.client.core.api.AlbumDetail
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DownloadExportPlanTest {
    @Test
    fun singleChapterPdfUsesPdfNameAndMimeType() {
        val plan = downloadExportPlan(listOf(album()), setOf("10"))
        assertEquals("pdf", plan.extension(DownloadExportFormat.PDF))
        assertEquals("application/pdf", plan.mimeType(DownloadExportFormat.PDF))
        assertEquals("book.pdf", plan.fileName(DownloadExportFormat.PDF))
        assertEquals("zip", plan.extension(DownloadExportFormat.ZIP))
        assertEquals("application/zip", plan.mimeType(DownloadExportFormat.ZIP))
    }

    @Test
    fun multipleChapterPdfsUseZipContainer() {
        val plan = downloadExportPlan(listOf(album(chapters = listOf(chapter("11"), chapter("12")))), setOf("10"))
        assertEquals(2, plan.chapterCount)
        assertEquals("book.zip", plan.fileName(DownloadExportFormat.PDF))
        assertEquals("application/zip", plan.mimeType(DownloadExportFormat.PDF))
    }

    @Test
    fun summaryCountsOnlySelectedCompleteChapters() {
        val partial = chapter("12").copy(expectedPageCount = 2)
        val plan = downloadExportPlan(listOf(album(chapters = listOf(chapter("11"), partial)), album("20")), setOf("10"))
        assertEquals(mapOf("10" to setOf("11")), plan.chapters)
        assertEquals(1, plan.albumCount)
        assertEquals(1, plan.chapterCount)
        assertEquals(1, plan.pageCount)
        assertEquals(1, plan.skippedChapters)
        assertEquals(100L, plan.sourceBytes)
    }

    @Test
    fun confirmedPlanKeepsItsChapterListWhenOtherDownloadsFinish() {
        val initial = album(chapters = listOf(chapter("11"), chapter("12").copy(pages = emptyList())))
        val plan = downloadExportPlan(listOf(initial), setOf("10"))
        val later = initial.copy(chapters = listOf(chapter("11"), chapter("12")))
        val resolved = resolveDownloadExport(listOf(later), plan.chapters) { File(it.relativePath) }
        assertEquals(listOf("chapter 11"), resolved.single().chapters.map { it.title })
        assertEquals("pdf", plan.extension(DownloadExportFormat.PDF))
    }

    @Test
    fun multipleAlbumsUseCollectionName() {
        val plan = downloadExportPlan(listOf(album(), album("20")), setOf("10", "20"))
        assertEquals(2, plan.albumCount)
        assertEquals("离线漫画合集.zip", plan.fileName(DownloadExportFormat.PDF))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySelectionIsRejected() {
        downloadExportPlan(listOf(album()), emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingSelectedAlbumIsRejected() {
        downloadExportPlan(listOf(album()), setOf("10", "20"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun eachSelectedAlbumMustHaveACompleteChapter() {
        downloadExportPlan(listOf(album(), album("20", listOf(chapter("21").copy(pages = emptyList())))), setOf("10", "20"))
    }

    private fun chapter(id: String) = OfflineChapter(id, "chapter $id", null, 1,
        listOf(OfflinePage(0, "10/$id/000000.png", 100, "digest")))

    private fun album(id: String = "10", chapters: List<OfflineChapter> = listOf(chapter("11"))): OfflineAlbum {
        val detail = AlbumDetail(
            id = id, name = "book", description = null, authors = emptyList(), imageCount = null,
            totalViews = null, likes = null, commentTotal = null, tags = emptyList(), actors = emptyList(),
            works = emptyList(), isFavorite = null, liked = null, related = emptyList(), series = emptyList(),
            seriesId = null, price = null, purchased = null, raw = emptyMap(),
        )
        return OfflineAlbum(id, "book", detail, chapters)
    }
}
