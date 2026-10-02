package app.prismia.plus

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadExportPngTest {
    @Test
    fun rgbPngEmbedsOriginalCompressedPixelsWithoutCallingImageEncoder() = runBlocking {
        withRoot { root ->
            val raw = pixels(32, 19, 3)
            val file = png(root, 32, 19, 2, raw)
            val expected = OfflinePage(0, "10/11/000000.png", file.length(), OfflineFiles.digest(file))
            val output = ByteArrayOutputStream()
            var completed = 0
            writeChapterPdf(DownloadExportChapter("test", listOf(file), listOf(expected)), output, {},
                encodeImage = { error("Eligible PNG must bypass bitmap encoding") }, onPageWritten = { completed++ })
            val bytes = output.toByteArray()
            val pdf = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(pdf.contains("/Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 3 /BitsPerComponent 8 /Columns 32"))
            val start = pdf.indexOf("stream\n") + 7
            val size = Regex("/Length ([0-9]+)").find(pdf)!!.groupValues[1].toInt()
            val restored = InflaterInputStream(bytes.copyOfRange(start, start + size).inputStream()).use { it.readBytes() }
            assertArrayEquals(raw, restored)
            assertEquals(1, completed)
            assertOffsets(pdf)
        }
    }

    @Test
    fun newlyRestoredStreamingPngUsesDirectPdfPath() = runBlocking {
        withRoot { root ->
            val width = 301
            val height = 279
            val pixels = IntArray(width * height) { 0xff000000.toInt() or (it * 197) }
            val file = File(root, "streaming.png")
            file.outputStream().use { output ->
                app.prismia.plus.core.image.StreamingPngEncoder.encode(width, height, output,
                    readArgbRows = { y, rows, dest -> pixels.copyInto(dest, 0, y * width, (y + rows) * width) },
                    moves = app.prismia.plus.core.image.ImagePipeline().restoreMoves(height, 10))
            }
            assertNotNull(readPdfPng(file))
            val expected = OfflinePage(0, "10/11/000000.png", file.length(), OfflineFiles.digest(file))
            writeChapterPdf(DownloadExportChapter("test", listOf(file), listOf(expected)), ByteArrayOutputStream(), {},
                encodeImage = { error("New restored PNG must not be re-encoded") })
        }
    }

    @Test
    fun grayscaleUsesDeviceGrayAndSingleColorPredictor() {
        withRoot { root ->
            val image = readPdfPng(png(root, 41, 12, 0, pixels(41, 12, 1)))!!
            assertEquals(1, image.colors)
            assertEquals("/DeviceGray", image.colorSpace)
        }
    }

    @Test
    fun multipleIdatChunksAreConcatenatedWithoutRecompression() {
        withRoot { root ->
            val raw = pixels(300, 300, 3)
            val file = png(root, 300, 300, 2, raw, split = true)
            val image = readPdfPng(file)!!
            assertEquals(2, image.ranges.size)
            val output = ByteArrayOutputStream()
            copyPdfPng(file, image, output, ByteArray(8192))
            assertArrayEquals(raw, InflaterInputStream(output.toByteArray().inputStream()).use { it.readBytes() })
        }
    }

    @Test
    fun transparencyLargeImagesInterlaceAndColorProfilesUseFallback() {
        withRoot { root ->
            assertNull(readPdfPng(png(root, 4, 4, 6, pixels(4, 4, 4))))
            assertNull(readPdfPng(png(root, 2049, 1, 2, pixels(2049, 1, 3))))
            assertNull(readPdfPng(png(root, 4, 4, 2, pixels(4, 4, 3), interlace = 1)))
            assertNull(readPdfPng(png(root, 4, 4, 2, pixels(4, 4, 3), extra = "iCCP")))
            assertNull(readPdfPng(png(root, 4, 4, 2, pixels(4, 4, 3), extra = "tRNS")))
        }
    }

    @Test
    fun corruptCrcTruncatedPixelsBadFiltersAndTrailingZlibDataAreRejected() {
        withRoot { root ->
            val raw = pixels(7, 8, 3)
            val file = png(root, 7, 8, 2, raw)
            val damaged = file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            file.writeBytes(damaged)
            assertNull(readPdfPng(file))
            assertNull(readPdfPng(png(root, 7, 8, 2, raw.copyOf(raw.size - 2))))
            assertNull(readPdfPng(png(root, 7, 8, 2, raw.copyOf(raw.size + 2))))
            assertNull(readPdfPng(png(root, 7, 8, 2, raw.copyOf().apply { this[0] = 5 })))
            assertNull(readPdfPng(png(root, 7, 8, 2, raw, trailingZlib = true)))
        }
    }

    @Test
    fun validationAndCopyRespectCancellationAndOutputFailures() {
        withRoot { root ->
            val file = png(root, 400, 400, 2, pixels(400, 400, 3))
            var checks = 0
            try {
                readPdfPng(file) { if (++checks == 4) throw CancellationException("test") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { assertEquals(4, checks) }
            val image = readPdfPng(file)!!
            try {
                copyPdfPng(file, image, ByteArrayOutputStream(), ByteArray(8192)) { throw CancellationException("test") }
                fail("Expected copy cancellation")
            } catch (_: CancellationException) { }
            val failing = object : OutputStream() {
                override fun write(value: Int) { throw IOException("test output failure") }
            }
            try {
                copyPdfPng(file, image, failing, ByteArray(8192))
                fail("Expected output failure")
            } catch (error: IOException) { assertEquals("test output failure", error.message) }
        }
    }

    private fun pixels(width: Int, height: Int, colors: Int): ByteArray {
        val row = width * colors + 1
        return ByteArray(row * height) { if (it % row == 0) 0 else (it * 31).toByte() }
    }

    private fun png(root: File, width: Int, height: Int, type: Int, raw: ByteArray,
        split: Boolean = false, interlace: Int = 0, extra: String? = null, trailingZlib: Boolean = false): File {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, data: ByteArray) {
            val typeBytes = name.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            output.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width); writeInt(height); writeByte(8); writeByte(type); writeByte(0); writeByte(0); writeByte(interlace)
        }
        chunk("IHDR", header.toByteArray())
        if (extra != null) chunk(extra, byteArrayOf(0))
        val compressed = ByteArrayOutputStream().also { target -> DeflaterOutputStream(target).use { it.write(raw) } }.toByteArray()
        val data = if (trailingZlib) compressed + byteArrayOf(1) else compressed
        if (split) {
            chunk("IDAT", data.copyOfRange(0, data.size / 2))
            chunk("IDAT", data.copyOfRange(data.size / 2, data.size))
        } else chunk("IDAT", data)
        chunk("IEND", byteArrayOf())
        return File(root, "test.png").apply { writeBytes(bytes.toByteArray()) }
    }

    private fun assertOffsets(pdf: String) {
        val xref = pdf.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(pdf.substring(xref).startsWith("xref\n"))
        pdf.substring(xref).lineSequence().drop(3).take(5).forEachIndexed { index, line ->
            val offset = line.substringBefore(' ').toInt()
            assertTrue(pdf.substring(offset).startsWith("${index + 1} 0 obj\n"))
        }
    }

    private inline fun <T> withRoot(block: (File) -> T): T {
        val root = Files.createTempDirectory("jmx-pdf-png").toFile()
        try { return block(root) } finally { root.deleteRecursively() }
    }
}
