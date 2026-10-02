package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadPresentationTest {
    @Test
    fun batchRetryOnlyIncludesFailedDownloadsAndDeduplicatesIds() {
        val failed = item("failed", retry = true)
        val paused = item("paused").copy(canResume = true, error = "下载已暂停")
        val running = item("running").copy(canPause = true)
        assertEquals(setOf("failed"), offlineRetryIds(listOf(failed, paused, running, failed)))
    }

    @Test
    fun emptyFailureListOffersNoBatchRetry() {
        assertTrue(offlineRetryIds(emptyList()).isEmpty())
        assertTrue(offlineRetryIds(listOf(item("done", completed = 2))).isEmpty())
    }

    @Test
    fun exportRequiresACompletedChapterForEverySelectedAlbum() {
        val items = listOf(item("ready", completed = 2), item("partial"))
        assertTrue(canExportOfflineSelection(items, setOf("ready")))
        assertFalse(canExportOfflineSelection(items, setOf("partial")))
        assertFalse(canExportOfflineSelection(items, setOf("ready", "partial")))
    }

    @Test
    fun failedAlbumCanStillExportItsCompletedChapters() {
        assertTrue(canExportOfflineSelection(listOf(item("failed", completed = 1, retry = true)), setOf("failed")))
    }

    @Test
    fun staleOrEmptySelectionDoesNotEnableExport() {
        val items = listOf(item("ready", completed = 1))
        assertFalse(canExportOfflineSelection(items, emptySet()))
        assertFalse(canExportOfflineSelection(items, setOf("removed")))
        assertFalse(canExportOfflineSelection(items, setOf("ready", "removed")))
        assertFalse(canExportOfflineSelection(listOf(item("invalid", completed = -1)), setOf("invalid")))
    }

    @Test
    fun unknownLaterChapterDoesNotShowFalseOverallPercentage() {
        val album = album().copy(chapters = listOf(chapter("11", 2, 2), chapter("12", 0, 0)))
        val ui = album.toDownloadUiModel()
        assertEquals(null, ui.progress)
        assertEquals("下载中", ui.statusLabel)
        assertEquals("chapter 11", ui.chapterLabel)
        assertEquals("本章 2 / 2 页", ui.pageLabel)
    }

    @Test
    fun knownTotalShowsProgressSeparatelyFromChapterName() {
        val ui = album().toDownloadUiModel()
        assertEquals(0.5f, ui.progress!!, 0.001f)
        assertEquals("下载中", ui.statusLabel)
        assertEquals("本章 1 / 2 页", ui.pageLabel)
    }

    @Test
    fun failedStateDoesNotRetainActiveChapterText() {
        val ui = album().copy(status = OfflineDownloadStatus.FAILED, error = "网络不可用").toDownloadUiModel()
        assertTrue(ui.canRetry)
        assertFalse(ui.canPause)
        assertEquals(null, ui.chapterLabel)
        assertEquals(null, ui.pageLabel)
        assertEquals("网络不可用", ui.error)
    }

    private fun chapter(id: String, total: Int, downloaded: Int) = OfflineChapter(id, "chapter $id", null, total,
        List(downloaded) { OfflinePage(it, "10/$id/${it.toString().padStart(6, '0')}.png", 1, "digest") })

    private fun album(): OfflineAlbum {
        val detail = dev.jmx.client.core.api.AlbumDetail(
            id = "10", name = "book", description = null, authors = emptyList(), imageCount = null,
            totalViews = null, likes = null, commentTotal = null, tags = emptyList(), actors = emptyList(),
            works = emptyList(), isFavorite = null, liked = null, related = emptyList(), series = emptyList(),
            seriesId = null, price = null, purchased = null, raw = emptyMap(),
        )
        return OfflineAlbum("10", "book", detail, listOf(chapter("11", 2, 1)),
            status = OfflineDownloadStatus.DOWNLOADING, currentChapterId = "11", currentChapterName = "chapter 11")
    }

    private fun item(id: String, completed: Int = 0, retry: Boolean = false) = OfflineDownloadUiModel(
        album = HomeAlbum(id, "测试漫画", "测试作者", "", ""),
        downloadedChapters = completed,
        totalChapters = 3,
        canRetry = retry,
    )
}
