package app.prismia.plus

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderOfflineModelsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun completeChapterUsesManifestOrderNotFileNameOrDirectoryContents() {
        val root = temporary.newFolder("book")
        val last = page(root, "z.jpg", 0)
        val first = page(root, "a.jpg", 1)
        File(root, "unlisted.jpg").writeText("must not be scanned")
        val manifest = manifest(root, chapter("one", listOf(first, last)))

        val inspection = inspectReaderOfflineManifest(manifest)

        assertNull(inspection.error)
        assertEquals(setOf("one"), inspection.availableChapterIds)
        assertEquals(listOf("z.jpg", "a.jpg"), inspection.chapters.single().files.map { it.file.name })
        assertEquals(listOf(0, 1), inspection.chapters.single().files.map { it.index })
    }

    @Test
    fun inspectionStillRejectsIncompleteEntriesFromLegacyManifests() {
        val root = temporary.newFolder("book")
        val downloaded = chapter("one", listOf(page(root, "one.jpg", 0)))
        val missing = ReaderOfflineChapter("two", "Chapter two", "2", 10)
        val inspection = inspectReaderOfflineManifest(manifest(root, downloaded, missing))

        assertEquals(listOf("one", "two"), inspection.chapters.map { it.chapter.id })
        assertEquals(setOf("one"), inspection.availableChapterIds)
        assertNotNull(inspection.chapters[1].error)
    }

    @Test
    fun missingFileInvalidatesEntireChapterInsteadOfShowingPartialPages() {
        val root = temporary.newFolder("book")
        val first = page(root, "1.jpg", 0)
        val second = page(root, "2.jpg", 1)
        File(root, "2.jpg").delete()
        val inspection = inspectReaderOfflineManifest(manifest(root, chapter("one", listOf(first, second))))

        assertTrue(inspection.availableChapterIds.isEmpty())
        assertTrue(inspection.chapters.single().files.isEmpty())
        assertTrue(inspection.chapters.single().error!!.contains("不会请求网络"))
    }

    @Test
    fun truncatedFileIsNotAcceptedEvenWhenPathExists() {
        val root = temporary.newFolder("book")
        val page = page(root, "one.jpg", 0)
        File(root, "one.jpg").writeText("x")

        assertInvalid(root, listOf(page))
    }

    @Test
    fun pageNumbersMustBeZeroBasedContinuousAndUnique() {
        val root = temporary.newFolder("book")
        val first = page(root, "1.jpg", 0)
        val second = page(root, "2.jpg", 1)
        assertInvalid(root, listOf(first, second.copy(index = 0)))
        assertInvalid(root, listOf(first, second.copy(index = 2)))
        assertInvalid(root, listOf(first.copy(index = -1), second))
    }

    @Test
    fun expectedPageCountMustMatchAndBePositive() {
        val root = temporary.newFolder("book")
        val page = page(root, "one.jpg", 0)
        listOf(0, -1, 2).forEach { count ->
            val value = ReaderOfflineChapter("one", null, null, count, listOf(page))
            assertTrue(inspectReaderOfflineManifest(manifest(root, value)).availableChapterIds.isEmpty())
        }
        assertTrue(inspectReaderOfflineManifest(manifest(root, chapter("one", emptyList()))).availableChapterIds.isEmpty())
    }

    @Test
    fun remoteAbsoluteTraversalAndUnfinishedPathsAreRejected() {
        val root = temporary.newFolder("book")
        val page = page(root, "one.jpg", 0)
        listOf(
            "https://example.invalid/one.jpg", "file:///one.jpg", "content://images/one.jpg",
            "/one.jpg", "C:\\one.jpg", "../one.jpg", "child/../../one.jpg", "./one.jpg",
            "child//one.jpg", "one.jpg.part", "one.jpg.tmp", "one.jpg\u0000", "",
        ).forEach { path -> assertInvalid(root, listOf(page.copy(relativePath = path))) }
    }

    @Test
    fun duplicateFileReferencesAreRejected() {
        val root = temporary.newFolder("book")
        val page = page(root, "one.jpg", 0)
        assertInvalid(root, listOf(page, page.copy(index = 1)))
    }

    @Test
    fun zeroByteFilesAndDirectoriesAreNotImages() {
        val root = temporary.newFolder("book")
        File(root, "empty.jpg").createNewFile()
        File(root, "folder.jpg").mkdir()
        assertInvalid(root, listOf(ReaderOfflinePage(0, "empty.jpg", 0)))
        assertInvalid(root, listOf(ReaderOfflinePage(0, "folder.jpg", 1)))
    }

    @Test
    fun symlinksCannotEscapeRootWhenSupported() {
        val root = temporary.newFolder("book")
        val external = temporary.newFile("outside.jpg").apply { writeText("image bytes") }
        val link = File(root, "link.jpg")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), external.toPath()) }.isSuccess
        assumeTrue("Symbolic links require permission on Windows", created)
        assertInvalid(root, listOf(ReaderOfflinePage(0, "link.jpg", external.length())))
    }

    @Test
    fun missingRootAndWrongAlbumFailClosed() {
        val root = temporary.newFolder("book")
        val value = manifest(root, chapter("one", listOf(page(root, "one.jpg", 0))))
        assertNotNull(inspectReaderOfflineManifest(value, "different").error)
        root.deleteRecursively()
        assertNotNull(inspectReaderOfflineManifest(value).error)
    }

    @Test
    fun emptyDuplicateAndBlankCatalogIdsAreRejected() {
        val root = temporary.newFolder("book")
        val chapter = chapter("one", listOf(page(root, "one.jpg", 0)))
        assertNotNull(inspectReaderOfflineManifest(manifest(root)).error)
        assertNotNull(inspectReaderOfflineManifest(manifest(root, chapter, chapter)).error)
        assertNotNull(inspectReaderOfflineManifest(manifest(root, chapter.copy(id = " "))).error)
    }

    @Test
    fun navigationSkipsOnlyUnavailableChaptersAndNeverWraps() {
        val chapters = listOf("one", "two", "three", "four").map { chapter(it, emptyList()) }
        val available = setOf("one", "three")
        assertEquals(2, readerOfflineAdjacentChapterIndex(chapters, available, 0, 1))
        assertEquals(0, readerOfflineAdjacentChapterIndex(chapters, available, 2, -1))
        assertNull(readerOfflineAdjacentChapterIndex(chapters, available, 2, 1))
        assertNull(readerOfflineAdjacentChapterIndex(chapters, available, 0, -1))
        assertNull(readerOfflineAdjacentChapterIndex(chapters, available, 0, 0))
        assertNull(readerOfflineAdjacentChapterIndex(chapters, available, -1, 1))
        assertNull(readerOfflineAdjacentChapterIndex(chapters, emptySet(), 1, 1))
    }

    @Test
    fun fileDeletionIsDetectedAgainOnChapterChangeOrRetry() {
        val root = temporary.newFolder("book")
        val page = page(root, "one.jpg", 0)
        val value = manifest(root, chapter("one", listOf(page)))
        assertEquals(setOf("one"), inspectReaderOfflineManifest(value).availableChapterIds)
        File(root, "one.jpg").delete()
        assertTrue(inspectReaderOfflineManifest(value).availableChapterIds.isEmpty())
        page(root, "one.jpg", 0)
        assertEquals(setOf("one"), inspectReaderOfflineManifest(value).availableChapterIds)
    }

    @Test
    fun localCacheIdentityIncludesFileVersionAndCannotMatchRemoteKey() {
        val root = temporary.newFolder("book")
        val file = File(root, "one.jpg")
        val original = ReaderOfflineFile(0, file, 12, 1000)
        assertTrue(original.cacheKey.startsWith("offline-restored:"))
        assertNotEquals(original.cacheKey, original.copy(byteCount = 13).cacheKey)
        assertNotEquals(original.cacheKey, original.copy(modifiedAt = 1001).cacheKey)
        assertNotEquals(original.cacheKey, original.copy(file = File(root, "two.jpg")).cacheKey)
        assertFalse(original.cacheKey.startsWith("http"))
    }

    @Test
    fun downloadedCatalogOmitsUnselectedAndIncompleteChaptersInOriginalOrder() {
        val root = temporary.newFolder("catalog")
        val complete = OfflineChapter("13", "third", "3", 1, listOf(OfflinePage(0, "10/13/000000.png", 10, "digest")))
        val first = complete.copy(id = "11", name = "first", sort = "1", pages = listOf(OfflinePage(0, "10/11/000000.png", 10, "digest")))
        val partial = complete.copy(id = "12", name = "second", sort = "2", expectedPageCount = 2)
        val album = offlineAlbum(listOf(complete, partial, first))
        val manifest = album.readerOfflineManifest(root)
        assertEquals(listOf("11", "13"), manifest.chapters.map { it.id })
        assertEquals(listOf("1", "3"), manifest.chapters.map { it.sort })
        assertEquals(2, manifest.chapters.sumOf { it.pages.size })
        assertEquals(1, readerOfflineAdjacentChapterIndex(manifest.chapters, setOf("11", "13"), 0, 1))
    }

    @Test
    fun emptyDownloadedCatalogDoesNotInventAnAlbumChapter() {
        val root = temporary.newFolder("empty-catalog")
        val album = offlineAlbum(listOf(OfflineChapter("11", "first", "1")))
        val manifest = album.readerOfflineManifest(root)
        assertTrue(manifest.chapters.isEmpty())
        assertNotNull(manifest.catalogError("10"))
    }

    @Test
    fun catalogIncludesNewlyCompletedChapterOnlyInNewSnapshot() {
        val root = temporary.newFolder("updated-catalog")
        val chapter = OfflineChapter("11", "first", "1", expectedPageCount = 1)
        val album = offlineAlbum(listOf(chapter))
        val before = album.readerOfflineManifest(root)
        val after = album.copy(chapters = listOf(chapter.copy(pages = listOf(OfflinePage(0, "10/11/000000.png", 1, "digest")))))
            .readerOfflineManifest(root)
        assertTrue(before.chapters.isEmpty())
        assertEquals(listOf("11"), after.chapters.map { it.id })
    }

    private fun offlineAlbum(chapters: List<OfflineChapter>): OfflineAlbum {
        val detail = app.prismia.plus.core.api.AlbumDetail(
            id = "10", name = "book", description = null, authors = emptyList(), imageCount = null,
            totalViews = null, likes = null, commentTotal = null, tags = emptyList(), actors = emptyList(),
            works = emptyList(), isFavorite = null, liked = null, related = emptyList(),
            series = listOf("11", "12", "13", "14").map { app.prismia.plus.core.api.AlbumChapter(it, null, null) },
            seriesId = null, price = null, purchased = null, raw = emptyMap(),
        )
        return OfflineAlbum("10", "book", detail, chapters)
    }

    private fun assertInvalid(root: File, pages: List<ReaderOfflinePage>) {
        val inspection = inspectReaderOfflineManifest(manifest(root, chapter("one", pages)))
        assertTrue(inspection.availableChapterIds.isEmpty())
        assertTrue(inspection.chapters.single().files.isEmpty())
        assertNotNull(inspection.chapters.single().error)
    }

    private fun page(root: File, path: String, index: Int): ReaderOfflinePage {
        val file = File(root, path).apply { requireNotNull(parentFile).mkdirs(); writeText("image bytes") }
        return ReaderOfflinePage(index, path, file.length())
    }

    private fun chapter(id: String, pages: List<ReaderOfflinePage>) =
        ReaderOfflineChapter(id, "Chapter $id", null, pages.size, pages)

    private fun manifest(root: File, vararg chapters: ReaderOfflineChapter) =
        ReaderOfflineManifest("album", root, chapters.toList())
}
