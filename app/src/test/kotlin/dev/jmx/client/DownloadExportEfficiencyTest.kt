package dev.jmx.client

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadExportEfficiencyTest {
    @Test
    fun snapshotPinsSelectedFilesAcrossReplacementAndDeletion() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-pin").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("original") }
            val cache = File(root, "cache")
            val snapshot = createDownloadExportSnapshot(listOf(album(source)), cache)
            val pinned = snapshot.albums.single().chapters.single().pages.single()
            assertTrue(Files.isSameFile(source.toPath(), pinned.toPath()))
            val replacement = File(root, "next.png").apply { writeText("replacement") }
            OfflineFiles.atomicMove(replacement, source)
            assertEquals("original", pinned.readText())
            source.delete()
            assertEquals("original", pinned.readText())
            snapshot.close()
            assertEquals(0, cache.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unsupportedLinksFallBackToCopyAndCleanup() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-copy").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("bytes") }
            val cache = File(root, "cache")
            createDownloadExportSnapshot(listOf(album(source)), cache, link = { _, _ -> throw UnsupportedOperationException() }).use {
                val copy = it.albums.single().chapters.single().pages.single()
                assertFalse(Files.isSameFile(source.toPath(), copy.toPath()))
                source.delete()
                assertEquals("bytes", copy.readText())
            }
            assertEquals(0, cache.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cancelledSnapshotLeavesNoTemporaryLinks() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-cancel").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("bytes") }
            val cache = File(root, "cache")
            val task = launch {
                createDownloadExportSnapshot(listOf(album(source).copy(chapters = listOf(DownloadExportChapter("chapter", listOf(source, source))))), cache,
                    link = { from, to ->
                        Files.createLink(to.toPath(), from.toPath())
                        coroutineContext.job.cancel()
                    })
            }
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(0, cache.listFiles()!!.size)
            assertEquals("bytes", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun zipVerifiesBytesInSamePassAndReportsCompletedPages() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-verify").toFile()
        try {
            val source = File(root, "page.png").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
            val expected = OfflinePage(0, "10/11/000000.png", source.length(), OfflineFiles.digest(source))
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Pair<Int, Int>>()
            exportOfflineDownloads(listOf(album(source, expected)), DownloadExportFormat.ZIP, output,
                onPageProgress = { done, total -> progress += done to total })
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                assertNotNull(zip.nextEntry)
                assertArrayEquals(source.readBytes(), zip.readBytes())
            }
            assertEquals(listOf(0 to 1, 1 to 1), progress)
            // Level 0 does not spend CPU recompressing image data.
            assertTrue(output.size() >= source.length())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun corruptPageCannotBeReportedAsSuccessfulExport() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-corrupt").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("valid") }
            val expected = OfflinePage(0, "10/11/000000.png", source.length(), OfflineFiles.digest(source))
            source.writeText("wrong")
            val progress = mutableListOf<Int>()
            try {
                exportOfflineDownloads(listOf(album(source, expected)), DownloadExportFormat.ZIP, ByteArrayOutputStream(),
                    onPageProgress = { done, _ -> progress += done })
                fail("Expected checksum failure")
            } catch (_: IllegalArgumentException) {
                assertEquals(listOf(0), progress)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun pdfValidatesOnlySelectedPageBeforeEncoding() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-pdf").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("valid") }
            val expected = OfflinePage(0, "10/11/000000.png", source.length(), "bad digest")
            var encoded = false
            try {
                writeChapterPdf(DownloadExportChapter("chapter", listOf(source), listOf(expected)), ByteArrayOutputStream(), {},
                    encodeImage = { encoded = true; PdfJpeg(1, 1, byteArrayOf(1)) })
                fail("Expected checksum failure")
            } catch (_: IllegalArgumentException) { assertFalse(encoded) }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun startupCleanupRemovesOnlyOwnedInterruptedSnapshots() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-recover").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("original") }
            val cache = File(root, "cache")
            createDownloadExportSnapshot(listOf(album(source)), cache)
            val unrelated = File(cache, "other-cache").apply { mkdirs() }
            File(unrelated, "important.txt").writeText("keep")
            val unexpected = File(cache, "export-123").apply { mkdirs() }
            File(unexpected, "unexpected.txt").writeText("keep")
            discardInterruptedDownloadExports(cache)
            assertEquals(setOf("other-cache", "export-123"), cache.listFiles()!!.map { it.name }.toSet())
            assertEquals("original", source.readText())
            assertEquals("keep", File(unexpected, "unexpected.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun missingLaterPageCleansAlreadyCreatedLinks() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-missing").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("bytes") }
            val cache = File(root, "cache")
            try {
                createDownloadExportSnapshot(listOf(DownloadExportAlbum("10", "book", listOf(
                    DownloadExportChapter("chapter", listOf(source, File(root, "missing.png"))),
                ))), cache)
                fail("Expected missing page failure")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, cache.listFiles()!!.size)
                assertEquals("bytes", source.readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun closingOneSnapshotDoesNotAffectAnother() = runBlocking {
        val root = Files.createTempDirectory("jmx-export-independent").toFile()
        try {
            val source = File(root, "page.png").apply { writeText("bytes") }
            val cache = File(root, "cache")
            val first = createDownloadExportSnapshot(listOf(album(source)), cache)
            val second = createDownloadExportSnapshot(listOf(album(source)), cache)
            first.close()
            assertEquals("bytes", second.albums.single().chapters.single().pages.single().readText())
            second.close()
            assertEquals(0, cache.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    private fun album(file: File, expected: OfflinePage? = null) = DownloadExportAlbum("10", "book", listOf(
        DownloadExportChapter("chapter", listOf(file), listOfNotNull(expected)),
    ))
}
