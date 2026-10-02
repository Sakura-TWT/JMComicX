package dev.jmx.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DownloadExportChapter(
    val title: String,
    val pages: List<File>,
    val expectedPages: List<OfflinePage> = emptyList(),
)
internal data class DownloadExportAlbum(val id: String, val title: String, val chapters: List<DownloadExportChapter>)

internal fun resolveDownloadExport(
    library: List<OfflineAlbum>,
    selectedChapters: Map<String, Set<String>>,
    pageFile: (OfflinePage) -> File,
): List<DownloadExportAlbum> = selectedChapters.map { (albumId, chapterIds) ->
    val album = requireNotNull(library.firstOrNull { it.id == albumId }) { "所选下载已发生变化，请重新选择" }
    val chapters = album.chapters.filter { it.id in chapterIds }
    require(chapterIds.isNotEmpty() && chapters.size == chapterIds.size && chapters.all { it.completed }) {
        "所选章节已发生变化或文件不完整，请重新选择"
    }
    DownloadExportAlbum(album.id, album.title, chapters.map { chapter ->
        DownloadExportChapter(chapter.name, chapter.pages.map(pageFile), chapter.pages)
    })
}

internal suspend fun exportOfflineDownloads(
    albums: List<DownloadExportAlbum>,
    format: DownloadExportFormat,
    output: OutputStream,
    onPageProgress: (Int, Int) -> Unit = { _, _ -> },
    onProgress: (String) -> Unit = {},
) = withContext(Dispatchers.IO) {
    require(albums.isNotEmpty() && albums.all { it.chapters.isNotEmpty() }) { "没有完整下载的章节可导出" }
    albums.forEach { album ->
        album.chapters.forEach { chapter ->
            require(chapter.expectedPages.isEmpty() || chapter.expectedPages.size == chapter.pages.size)
            require(chapter.pages.isNotEmpty() && chapter.pages.all { it.isFile && it.length() > 0L }) {
                "${chapter.title} 的本地文件不完整，请重新下载"
            }
        }
    }
    val count = albums.sumOf { it.chapters.size }
    val totalPages = albums.sumOf { album -> album.chapters.sumOf { it.pages.size } }
    var completedPages = 0
    val buffer = ByteArray(64 * 1024)
    fun pageCompleted() { onPageProgress(++completedPages, totalPages) }
    onPageProgress(0, totalPages)
    if (format == DownloadExportFormat.PDF && count == 1) {
        val album = albums.single()
        writeChapterPdf(album.chapters.single(), output,
            progress = { onProgress("${album.title}\n$it") }, onPageWritten = ::pageCompleted)
    } else {
        ZipOutputStream(output).use { zip ->
            // Images and PDF image streams are already compressed; a second deflate pass wastes CPU.
            zip.setLevel(Deflater.NO_COMPRESSION)
            albums.forEachIndexed { albumIndex, album ->
                val albumName = "${(albumIndex + 1).toString().padStart(3, '0')}_${downloadExportName(album.title, "漫画")}_${downloadExportName(album.id, "id")}"
                album.chapters.forEachIndexed { chapterIndex, chapter ->
                    currentCoroutineContext().ensureActive()
                    val chapterName = downloadChapterExportName(chapterIndex, chapter.title)
                    if (format == DownloadExportFormat.PDF) {
                        zip.putNextEntry(ZipEntry("$albumName/$chapterName.pdf"))
                        writeChapterPdf(chapter, zip, progress = { onProgress("${album.title}\n$it") },
                            onPageWritten = ::pageCompleted)
                        zip.closeEntry()
                    } else {
                        chapter.pages.forEachIndexed { pageIndex, file ->
                            currentCoroutineContext().ensureActive()
                            onProgress("${album.title}\n${chapter.title} · ${pageIndex + 1} / ${chapter.pages.size} 页")
                            val extension = file.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heif", "heic") } ?: "img"
                            zip.putNextEntry(ZipEntry("$albumName/$chapterName/${(pageIndex + 1).toString().padStart(5, '0')}.$extension"))
                            copyVerifiedExportPage(file, chapter.expectedPages.getOrNull(pageIndex), zip, buffer)
                            zip.closeEntry()
                            pageCompleted()
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun copyVerifiedExportPage(file: File, expected: OfflinePage?, output: OutputStream?, buffer: ByteArray) {
    val digest = expected?.let { MessageDigest.getInstance("SHA-256") }
    var bytes = 0L
    file.inputStream().use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output?.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
            bytes += read
        }
    }
    if (expected != null) {
        val actual = requireNotNull(digest).digest().joinToString("") { "%02x".format(it) }
        require(bytes == expected.byteCount && actual == expected.sha256) { "部分章节文件校验失败，请重新下载后导出" }
    }
}

// Each page is emitted immediately; retaining a PdfDocument would keep every page's image in native memory.
internal suspend fun writeChapterPdf(
    chapter: DownloadExportChapter,
    target: OutputStream,
    progress: (String) -> Unit,
    encodeImage: (File) -> PdfJpeg = ::jpegForPdf,
    onPageWritten: () -> Unit = {},
) {
    val validationBuffer = ByteArray(64 * 1024)
    val output = CountingPdfOutput(target)
    val offsets = LongArray(3 + chapter.pages.size * 3)
    fun text(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
    fun objectStart(id: Int) {
        offsets[id] = output.count
        text("$id 0 obj\n")
    }
    text("%PDF-1.4\n")
    objectStart(1)
    text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    objectStart(2)
    text("<< /Type /Pages /Count ${chapter.pages.size} /Kids [")
    chapter.pages.indices.forEach { text("${3 + it * 3} 0 R ") }
    text("] >>\nendobj\n")
    chapter.pages.forEachIndexed { index, file ->
        currentCoroutineContext().ensureActive()
        progress("${chapter.title} · ${index + 1} / ${chapter.pages.size} 页")
        chapter.expectedPages.getOrNull(index)?.let { copyVerifiedExportPage(file, it, null, validationBuffer) }
        val context = currentCoroutineContext()
        val png = readPdfPng(file) { context.ensureActive() }
        val image = if (png == null) encodeImage(file) else null
        context.ensureActive()
        val imageWidth = png?.width ?: requireNotNull(image).width
        val imageHeight = png?.height ?: requireNotNull(image).height
        require(imageWidth > 0 && imageHeight > 0 && (png != null || image?.bytes?.isNotEmpty() == true)) { "PDF 图片数据无效" }
        val pageId = 3 + index * 3
        val pageWidth = minOf(595.0, 14_400.0 * imageWidth / imageHeight)
        val pageHeight = (pageWidth * imageHeight / imageWidth).coerceAtLeast(1.0)
        val width = String.format(Locale.ROOT, "%.3f", pageWidth)
        val height = String.format(Locale.ROOT, "%.3f", pageHeight)
        objectStart(pageId)
        text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /XObject << /Im0 ${pageId + 1} 0 R >> >> /Contents ${pageId + 2} 0 R >>\nendobj\n")
        objectStart(pageId + 1)
        if (png != null) {
            text("<< /Type /XObject /Subtype /Image /Width ${png.width} /Height ${png.height} /ColorSpace ${png.colorSpace} /BitsPerComponent 8 /Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors ${png.colors} /BitsPerComponent 8 /Columns ${png.width} >> /Length ${png.length} >>\nstream\n")
            copyPdfPng(file, png, output, validationBuffer) { context.ensureActive() }
        } else {
            val jpeg = requireNotNull(image)
            text("<< /Type /XObject /Subtype /Image /Width ${jpeg.width} /Height ${jpeg.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.bytes.size} >>\nstream\n")
            output.write(jpeg.bytes)
        }
        text("\nendstream\nendobj\n")
        val commands = "q $width 0 0 $height 0 0 cm /Im0 Do Q\n"
        objectStart(pageId + 2)
        text("<< /Length ${commands.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${commands}endstream\nendobj\n")
        onPageWritten()
    }
    val crossReference = output.count
    text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
    for (id in 1 until offsets.size) {
        require(offsets[id] <= 9_999_999_999L) { "PDF 超过格式大小限制，请分章导出" }
        text(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id]))
    }
    text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$crossReference\n%%EOF\n")
    output.flush()
}

private class CountingPdfOutput(target: OutputStream) : FilterOutputStream(target) {
    var count: Long = 0
        private set
    override fun write(value: Int) { out.write(value); count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count += length
    }
}

internal data class PdfJpeg(val width: Int, val height: Int, val bytes: ByteArray)

private fun jpegForPdf(file: File): PdfJpeg {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取导出图片" }
    val ratio = maxOf(bounds.outWidth / 2048.0, sqrt(bounds.outWidth.toDouble() * bounds.outHeight / 4_000_000.0), 1.0)
    var sample = 1
    while (sample * 2 <= ratio) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("无法解码导出图片")
    var flattened: Bitmap? = null
    try {
        val width = (bounds.outWidth / ratio).toInt().coerceAtLeast(1)
        val height = (bounds.outHeight / ratio).toInt().coerceAtLeast(1)
        val bitmap = if (decoded.width == width && decoded.height == height && !decoded.hasAlpha()) {
            decoded
        } else {
            createBitmap(width, height).also { target ->
                flattened = target
                Canvas(target).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }
        val bytes = ByteArrayOutputStream().use { encoded ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, encoded)) { "PDF 图片编码失败" }
            encoded.toByteArray()
        }
        return PdfJpeg(width, height, bytes)
    } finally {
        flattened?.recycle()
        decoded.recycle()
    }
}
