package app.prismia.plus

import app.prismia.plus.core.api.AlbumChapter
import app.prismia.plus.core.api.AlbumDetail
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineDownloadModelsTest {
    @Test fun metadataRoundTripWhitelistsDetailsAndNeverStoresCredentials() {
        val detail = detail().copy(raw = mapOf("password" to "never-persist-me", "token" to "secret-token"),
            isFavorite = true, liked = true, purchased = true)
        val album = OfflineAlbum("10", "Synthetic title", detail, listOf(OfflineChapter("11", "Chapter", "1")))
        val encoded = OfflineMetadata.encode(listOf(album))
        assertFalse(encoded.contains("never-persist-me"))
        assertFalse(encoded.contains("secret-token"))
        assertFalse(encoded.contains("raw"))
        assertFalse(encoded.contains("isFavorite"))
        val restored = OfflineMetadata.decode(encoded).single()
        assertEquals("Synthetic title", restored.title)
        assertEquals(detail.description, restored.detail.description)
        assertEquals(detail.series, restored.detail.series)
        assertTrue(restored.detail.raw.isEmpty())
        assertNull(restored.detail.isFavorite)
        assertNull(restored.detail.liked)
        assertNull(restored.detail.purchased)
        assertEquals(album.chapters, restored.chapters)
    }

    @Test fun completedRequiresExpectedCountAndContinuousZeroBasedPages() {
        val page = OfflinePage(0, "10/11/000000.png", 3, "abc")
        assertFalse(OfflineChapter("11", "Chapter", "1").completed)
        assertFalse(OfflineChapter("11", "Chapter", "1", 2, listOf(page)).completed)
        assertFalse(OfflineChapter("11", "Chapter", "1", 1, listOf(page.copy(index = 1))).completed)
        assertFalse(OfflineChapter("11", "Chapter", "1", 1, listOf(page.copy(byteCount = 0))).completed)
        assertTrue(OfflineChapter("11", "Chapter", "1", 1, listOf(page)).completed)
    }

    @Test fun recoveryDetectsSameLengthCorruptionAndDropsSuffixAfterGap() = inDirectory { root ->
        val pages = (0..2).map { page(root, it, "image-$it") }
        val album = OfflineAlbum("10", "Title", detail(),
            listOf(OfflineChapter("11", "Chapter", "1", 3, pages)), OfflineDownloadStatus.COMPLETED)
        OfflineFiles.resolve(root, pages[1].relativePath).writeText("damage!") // Same length as image-1.
        val recovered = OfflineFiles.recover(root, album)
        assertEquals(OfflineDownloadStatus.FAILED, recovered.status)
        assertEquals(listOf(pages[0]), recovered.chapters.single().pages)
        assertNotNull(recovered.error)
        assertTrue(recovered.coverUrl.startsWith("file:"))
        assertEquals(1, recovered.downloadedPages)
        assertEquals(3, recovered.totalPages)
    }

    @Test fun recoveryQueuesInterruptedButPreservesExplicitPauseAndNeverPromotesOrphanFiles() = inDirectory { root ->
        val page = page(root, 0, "image")
        page(root, 1, "orphan")
        val album = OfflineAlbum("10", "Title", detail(),
            listOf(OfflineChapter("11", "Chapter", "1", 2, listOf(page))), OfflineDownloadStatus.DOWNLOADING)
        assertEquals(OfflineDownloadStatus.QUEUED, OfflineFiles.recover(root, album).status)
        assertEquals(1, OfflineFiles.recover(root, album).downloadedPages)
        assertEquals(OfflineDownloadStatus.PAUSED,
            OfflineFiles.recover(root, album.copy(status = OfflineDownloadStatus.PAUSED)).status)
    }

    @Test fun recoveryKeepsValidCompletedChapterAndRecreatesLocalCover() = inDirectory { root ->
        val page = page(root, 0, "image")
        val album = OfflineAlbum("10", "Title", detail(),
            listOf(OfflineChapter("11", "Chapter", "1", 1, listOf(page))), OfflineDownloadStatus.DOWNLOADING,
            coverUrl = "https://not-persisted.invalid/remote")
        val restored = OfflineFiles.recover(root, OfflineMetadata.decode(OfflineMetadata.encode(listOf(album))).single())
        assertEquals(OfflineDownloadStatus.COMPLETED, restored.status)
        assertEquals(1, restored.completedChapters)
        assertEquals(5L, restored.byteCount)
        assertEquals(OfflineFiles.resolve(root, page.relativePath).toURI().toString(), restored.toHomeAlbum().coverUrl)
    }

    @Test fun missingAndTruncatedPagesAreNotComplete() = inDirectory { root ->
        val page = page(root, 0, "image")
        assertTrue(OfflineFiles.valid(root, page))
        OfflineFiles.resolve(root, page.relativePath).writeText("x")
        assertFalse(OfflineFiles.valid(root, page))
        OfflineFiles.resolve(root, page.relativePath).delete()
        assertFalse(OfflineFiles.valid(root, page))
    }

    @Test fun traversalAbsolutePathsAndCrossAlbumPageRecordsAreRejected() = inDirectory { root ->
        listOf("../escape.png", "10/11/../../evil.png", "C:/evil.png", "/10/11/000000.png",
            "10\\11\\000000.png", "10/11/000000.part", "10/11/000000.png/extra").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { OfflineFiles.resolve(root, path) }
        }
        assertThrows(IllegalArgumentException::class.java) { OfflineFiles.albumDirectory(root, "../evil") }
        val page = page(root, 0, "image")
        val album = OfflineAlbum("12", "Wrong owner", detail().copy(id = "12"),
            listOf(OfflineChapter("11", "Chapter", "1", 1, listOf(page))), OfflineDownloadStatus.COMPLETED)
        assertFalse(OfflineFiles.recover(root, album).chapters.single().completed)
    }

    @Test fun atomicMetadataReplacementLeavesNoTempAndDoesNotReadUncommittedTemp() = inDirectory { root ->
        val target = File(root, "library.json")
        val old = OfflineMetadata.encode(emptyList())
        OfflineFiles.atomicWrite(target, old)
        File(root, "library.json.tmp").writeText("crash midway")
        assertEquals(emptyList<OfflineAlbum>(), OfflineMetadata.decode(target.readText()))
        val next = listOf(OfflineAlbum("10", "Title", detail(), listOf(OfflineChapter("11", "Chapter", "1"))))
        OfflineFiles.atomicWrite(target, OfflineMetadata.encode(next))
        assertEquals("10", OfflineMetadata.decode(target.readText()).single().id)
        assertFalse(File(root, "library.json.tmp").exists())
    }

    @Test fun corruptOrDuplicateMetadataFailsClosedInsteadOfReplacingLibraryWithEmptyList() {
        assertThrows(Exception::class.java) { OfflineMetadata.decode("{invalid") }
        assertThrows(Exception::class.java) { OfflineMetadata.decode("{\"version\":9,\"albums\":[]}") }
        val album = OfflineAlbum("10", "Title", detail(), listOf(OfflineChapter("11", "Chapter", "1")))
        assertThrows(Exception::class.java) { OfflineMetadata.decode(OfflineMetadata.encode(listOf(album, album))) }
    }

    @Test fun syntheticDiskBudgetGivesExplicitStorageMessage() = inDirectory { root ->
        val full = object : File(root.path) { override fun getUsableSpace(): Long = 1024L }
        val error = assertThrows(OfflineStorageException::class.java) { OfflineFiles.ensureSpace(full) }
        assertTrue(error.message.orEmpty().contains("存储空间不足"))
    }

    private fun page(root: File, index: Int, value: String): OfflinePage {
        val relative = "10/11/${index.toString().padStart(6, '0')}.png"
        val file = OfflineFiles.resolve(root, relative)
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(value)
        return OfflinePage(index, relative, file.length(), OfflineFiles.digest(file))
    }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("jmx-offline-synthetic").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun detail() = AlbumDetail("10", "Title", "Saved description", listOf("Synthetic author"), 3,
        1, 1, 0, listOf("tag"), emptyList(), emptyList(), null, null, emptyList(),
        listOf(AlbumChapter("11", "Chapter", "1"), AlbumChapter("12", "Not downloaded", "2")),
        "10", null, null, emptyMap())
}
