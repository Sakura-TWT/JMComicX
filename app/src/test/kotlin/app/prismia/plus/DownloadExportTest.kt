package app.prismia.plus

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExportTest {
    @Test
    fun zipUsesOnlyOrderedManifestFilesAndKeepsChapterHierarchy() = runBlocking {
        val root = Files.createTempDirectory("jmx-export").toFile()
        try {
            val one = File(root, "one.png").apply { writeBytes(byteArrayOf(1, 2)) }
            val two = File(root, "two.png").apply { writeBytes(byteArrayOf(3, 4)) }
            File(root, "private.txt").writeText("not part of manifest")
            val output = ByteArrayOutputStream()
            exportOfflineDownloads(listOf(DownloadExportAlbum("123", "Test", listOf(
                DownloadExportChapter("A", listOf(two, one)),
                DownloadExportChapter("B", listOf(one)),
            ))), DownloadExportFormat.ZIP, output)
            val names = mutableListOf<String>()
            val contents = mutableListOf<List<Byte>>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                    contents += zip.readBytes().toList()
                }
            }
            assertEquals(listOf("001_Test_123/0001_A/00001.png", "001_Test_123/0001_A/00002.png", "001_Test_123/0002_B/00001.png"), names)
            assertEquals(listOf<Byte>(3, 4), contents.first())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun exportSnapshotDoesNotIncludeChaptersCompletedWhileChoosingDestination() {
        val album = exportAlbum()
        val resolved = resolveDownloadExport(listOf(album), mapOf("10" to setOf("11"))) { File(it.relativePath) }
        assertEquals(listOf("first"), resolved.single().chapters.map { it.title })
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportSnapshotRejectsLostChapterInsteadOfChangingContainer() {
        val album = exportAlbum()
        resolveDownloadExport(listOf(album.copy(chapters = album.chapters.take(1))), mapOf("10" to setOf("11", "12"))) {
            File(it.relativePath)
        }
    }

    @Test
    fun pdfLongImagesRemainWithinStandardPageDimensions() = runBlocking {
        val output = ByteArrayOutputStream()
        writeChapterPdf(DownloadExportChapter("long", listOf(File("a"))), output, {},
            encodeImage = { PdfJpeg(10, 100_000, byteArrayOf(1)) })
        val document = output.toString(Charsets.US_ASCII.name())
        val dimensions = Regex("/MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)]").find(document)!!
        assertTrue(dimensions.groupValues[1].toDouble() > 0)
        assertTrue(dimensions.groupValues[2].toDouble() <= 14_400)
    }

    private fun exportAlbum(): OfflineAlbum {
        val detail = app.prismia.plus.core.api.AlbumDetail(
            id = "10", name = "book", description = null, authors = emptyList(), imageCount = null,
            totalViews = null, likes = null, commentTotal = null, tags = emptyList(), actors = emptyList(),
            works = emptyList(), isFavorite = null, liked = null, related = emptyList(), series = emptyList(),
            seriesId = null, price = null, purchased = null, raw = emptyMap(),
        )
        return OfflineAlbum("10", "book", detail, listOf(
            OfflineChapter("11", "first", "1", 1, listOf(OfflinePage(0, "10/11/000000.png", 1, "digest"))),
            OfflineChapter("12", "second", "2", 1, listOf(OfflinePage(0, "10/12/000000.png", 1, "digest"))),
        ))
    }

    @Test
    fun pdfCrossReferenceOffsetsPointToEveryObject() = runBlocking {
        val output = ByteArrayOutputStream()
        writeChapterPdf(
            DownloadExportChapter("chapter", listOf(File("a"), File("b"))),
            output,
            progress = {},
            encodeImage = { PdfJpeg(10, 20, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())) },
        )
        val document = output.toString(Charsets.ISO_8859_1.name())
        assertTrue(document.contains("/Count 2"))
        val xrefOffset = document.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(document.substring(xrefOffset).startsWith("xref\n"))
        val offsets = document.substring(xrefOffset).lineSequence().drop(3).take(8).toList()
        offsets.forEachIndexed { index, line ->
            val offset = line.substringBefore(' ').toInt()
            assertTrue(document.substring(offset).startsWith("${index + 1} 0 obj\n"))
        }
    }
}
