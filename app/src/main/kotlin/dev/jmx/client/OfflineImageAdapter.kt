package dev.jmx.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import dev.jmx.client.core.download.ByteSink
import dev.jmx.client.core.download.Downloader
import dev.jmx.client.core.download.DownloadSinkException
import dev.jmx.client.core.download.TruncatingSink
import dev.jmx.client.core.image.ImageDownloadRequest
import dev.jmx.client.core.image.ImagePipeline
import dev.jmx.client.core.image.StreamingPngEncoder
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Compressed bytes stream straight to disk, so several pages may download at once; decoding then
 * happens one page at a time, holding at most two bounded bitmaps.
 * The protocol (request headers/URLs, scramble plan and row ordering) stays in core.
 */
internal data class OfflineImageResult(val file: File, val extension: String)

internal class OfflineImageAdapter(private val downloader: Downloader) {
    private val pipeline = ImagePipeline()

    suspend fun download(request: ImageDownloadRequest, root: File, target: File): OfflineImageResult {
        val context = currentCoroutineContext()
        val parent = requireNotNull(target.parentFile) { "章节目录无效" }
        val raw = File(parent, target.name + ".source.part")
        val output = File(parent, target.name + ".part")
        var completed = false
        try {
            OfflineFiles.ensureSpace(root, MAX_COMPRESSED_BYTES)
            OfflineFiles.ensureDirectory(parent)
            val result = RandomAccessFile(raw, "rw").use { file ->
                file.setLength(0)
                val sink = object : ByteSink, TruncatingSink {
                    private var untilSpaceCheck = 0L
                    override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
                    override fun write(bytes: ByteArray, offset: Int, byteCount: Int) {
                        context.ensureActive()
                        if (untilSpaceCheck < byteCount) {
                            OfflineFiles.ensureSpace(root, SPACE_CHECK_BYTES)
                            untilSpaceCheck = SPACE_CHECK_BYTES
                        }
                        file.write(bytes, offset, byteCount)
                        untilSpaceCheck -= byteCount
                    }
                    override fun truncate() { file.setLength(0); file.seek(0); untilSpaceCheck = 0 }
                }
                downloader.download(request.copy(maxBytes = MAX_COMPRESSED_BYTES).toDownloadRequest(), sink)
            }
            context.ensureActive()
            val download = when (result) {
                is JmxResult.Success -> result.value
                is JmxResult.Failure -> {
                    if (generateSequence(result.error.cause) { it.cause }.take(12)
                            .any { it is DownloadSinkException || it is OfflineStorageException }) {
                        throw OfflineStorageException("离线文件写入失败，请检查存储空间后继续下载", result.error.cause)
                    }
                    // Do not persist protocol URLs, response body samples or credentials in task errors.
                    throw OfflineStorageException("图片下载失败，请检查网络后继续下载")
                }
            }
            require(raw.length() > 0 && raw.length() == download.bytesWritten &&
                (download.contentLength < 0 || raw.length() == download.contentLength)) { "图片下载不完整" }
            // Failover can change the extension: the actual returned URL owns the image plan.
            // 下载阶段可以多页并发，解码/还原阶段必须全局串行：位图预算是按"同一时刻只有一页"
            // 算出来的，并发解码会把它乘上并发数，直接把大页漫画撞成 OOM。
            val extension = decodeGate.withPermit {
                val plan = pipeline.plan(download.url, request.albumId, request.scrambleId)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(raw.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式损坏或无法解码" }
                val pixels = bounds.outWidth.toLong() * bounds.outHeight
                // 80 MiB of pixel storage max; never silently downsample the user's offline original.
                if (pixels * (if (plan.requiresRestore) 8L else 4L) > MAX_BITMAP_BYTES) {
                    throw OfflineStorageException("单页图片超过离线处理内存预算，未降低画质；请稍后重试")
                }
                val source = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: throw OfflineStorageException("图片解码失败，下载内容不完整")
                try {
                    require(source.width == bounds.outWidth && source.height == bounds.outHeight) { "图片尺寸不匹配" }
                    context.ensureActive()
                    if (!plan.requiresRestore) {
                        // Only durable page bytes need syncing; scrambled input is disposable after restoration.
                        RandomAccessFile(raw, "rw").use { it.fd.sync() }
                        OfflineFiles.atomicMove(raw, output) // Keep GIF animation and original encoded bytes intact.
                    } else {
                        OfflineFiles.ensureSpace(root, pixels * 5L)
                        val moves = pipeline.restoreMoves(source.height, plan.segmentCount)
                        // Wide-gamut and transparent pages retain Android's color/alpha-aware path.
                        val streaming = !source.hasAlpha() && source.colorSpace?.isSrgb == true && source.width <= 65_536
                        if (streaming) {
                            FileOutputStream(output).use { stream ->
                                BufferedOutputStream(stream, 64 * 1024).use { buffered ->
                                    StreamingPngEncoder.encode(
                                        width = source.width,
                                        height = source.height,
                                        output = buffered,
                                        readArgbRows = { y, rows, pixels -> source.getPixels(pixels, 0, source.width, 0, y, source.width, rows) },
                                        moves = moves,
                                        checkActive = { context.ensureActive() },
                                    )
                                    buffered.flush()
                                    stream.fd.sync()
                                }
                            }
                        } else {
                            val restored = createBitmap(source.width, source.height)
                            try {
                                val canvas = Canvas(restored)
                                val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
                                moves.forEach { move ->
                                    context.ensureActive()
                                    if (move.height > 0) canvas.drawBitmap(source,
                                        Rect(0, move.sourceY, source.width, move.sourceY + move.height),
                                        Rect(0, move.targetY, source.width, move.targetY + move.height), paint)
                                }
                                FileOutputStream(output).use { stream ->
                                    BufferedOutputStream(stream, 64 * 1024).use { buffered ->
                                        check(restored.compress(Bitmap.CompressFormat.PNG, 100, buffered)) { "图片保存失败" }
                                        buffered.flush()
                                        stream.fd.sync()
                                    }
                                }
                            } finally { restored.recycle() }
                        }
                    }
                } finally { source.recycle() }
                context.ensureActive()
                require(output.isFile && output.length() > 0) { "离线页面为空" }
                if (plan.requiresRestore) "png" else when (bounds.outMimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/avif" -> "avif"
                    "image/heif" -> "heif"
                    "image/heic" -> "heic"
                    else -> throw OfflineStorageException("离线图片格式不受支持")
                }
            }
            completed = true
            return OfflineImageResult(output, extension)
        } finally {
            raw.delete()
            if (!completed || !context.isActiveForOffline()) output.delete()
        }
    }

    companion object {
        const val MAX_COMPRESSED_BYTES = 32L * 1024 * 1024
        const val MAX_BITMAP_BYTES = 80L * 1024 * 1024
        private const val SPACE_CHECK_BYTES = 1024L * 1024

        /** Process-wide: the bitmap budget above is per page, so only one page may decode at a time. */
        private val decodeGate = Semaphore(1)
    }
}

private fun kotlin.coroutines.CoroutineContext.isActiveForOffline(): Boolean =
    this[kotlinx.coroutines.Job]?.isActive != false
