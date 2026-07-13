package dev.jmx.client.core.image

import dev.jmx.client.core.download.DownloadRequest
import dev.jmx.client.core.download.DownloadObserver
import dev.jmx.client.core.download.DownloadResult
import dev.jmx.client.core.download.Downloader
import dev.jmx.client.core.download.MemoryByteSink
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class DecodedImageRows(
    val width: Int,
    val height: Int,
    val bytesPerRow: Int,
    val rows: ByteArray
) {
    init {
        require(width >= 0) { "width must be >= 0" }
        require(height >= 0) { "height must be >= 0" }
        require(bytesPerRow >= 0) { "bytesPerRow must be >= 0" }
        require(rows.size == height * bytesPerRow) {
            "rows size ${rows.size} does not match height * bytesPerRow ${height * bytesPerRow}"
        }
    }

    fun copy(
        width: Int = this.width,
        height: Int = this.height,
        bytesPerRow: Int = this.bytesPerRow,
        rows: ByteArray = this.rows
    ): DecodedImageRows {
        return DecodedImageRows(width, height, bytesPerRow, rows)
    }
}

class RestoredImageBytes(
    val bytes: ByteArray,
    val contentType: String?
)

interface ImageRowCodec {
    fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows>
    fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes>
}

data class ImageDownloadRequest(
    val sourceUrl: String,
    val albumId: Int,
    val scrambleId: Int,
    val headers: Map<String, String> = emptyMap(),
    val acceptedContentTypes: Set<String> = setOf("image/*"),
    val maxBytes: Long? = null,
    val observer: DownloadObserver = DownloadObserver.None
) {
    fun toDownloadRequest(): DownloadRequest {
        return DownloadRequest(
            url = sourceUrl,
            headers = headers,
            acceptedContentTypes = acceptedContentTypes,
            maxBytes = maxBytes,
            observer = observer
        )
    }
}

class ImageRestoreResult(
    val plan: ImagePlan,
    val download: DownloadResult,
    val bytes: ByteArray,
    val contentType: String?,
    val restored: Boolean
)

class ImageRestoreExecutor(
    private val downloader: Downloader,
    private val codec: ImageRowCodec,
    private val imagePipeline: ImagePipeline = ImagePipeline()
) {
    suspend fun downloadAndRestore(request: ImageDownloadRequest): JmxResult<ImageRestoreResult> {
        val plan = imagePipeline.plan(request.sourceUrl, request.albumId, request.scrambleId)
        val sink = MemoryByteSink()
        val download = when (val result = downloader.download(request.toDownloadRequest(), sink)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val downloadedBytes = sink.bytes()
        if (!plan.requiresRestore) {
            return JmxResult.Success(
                ImageRestoreResult(
                    plan = plan,
                    download = download,
                    bytes = downloadedBytes,
                    contentType = download.contentType,
                    restored = false
                )
            )
        }
        val decoded = codec.decode(downloadedBytes, download.contentType).unwrapOrReturn { return it }
        val restoredRows = runCatching {
            imagePipeline.restoreRows(
                source = decoded.rows,
                imageHeight = decoded.height,
                bytesPerRow = decoded.bytesPerRow,
                segmentCount = plan.segmentCount
            )
        }.getOrElse {
            return JmxResult.Failure(JmxError.Schema("图片行复原失败", field = "image.rows", cause = it))
        }
        val restored = codec.encode(
            decoded.copy(rows = restoredRows),
            download.contentType
        ).unwrapOrReturn { return it }
        return JmxResult.Success(
            ImageRestoreResult(
                plan = plan,
                download = download,
                bytes = restored.bytes,
                contentType = restored.contentType,
                restored = true
            )
        )
    }

    private inline fun <T> JmxResult<T>.unwrapOrReturn(returnFailure: (JmxResult.Failure) -> Nothing): T {
        return when (this) {
            is JmxResult.Success -> value
            is JmxResult.Failure -> returnFailure(this)
        }
    }
}
