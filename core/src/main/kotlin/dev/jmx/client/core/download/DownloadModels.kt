package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

data class DownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val acceptedContentTypes: Set<String> = emptySet(),
    val maxBytes: Long? = null,
    val observer: DownloadObserver = DownloadObserver.None
)

data class DownloadResult(
    val url: String,
    val statusCode: Int,
    val contentType: String?,
    val contentLength: Long,
    val bytesWritten: Long
)

data class DownloadBatchItem(
    val index: Int,
    val request: DownloadRequest
)

data class DownloadBatchResult(
    val item: DownloadBatchItem,
    val result: JmxResult<DownloadResult>
)

sealed interface DownloadEvent {
    val url: String

    data class Started(
        override val url: String
    ) : DownloadEvent

    data class Progress(
        override val url: String,
        val bytesRead: Long,
        val contentLength: Long
    ) : DownloadEvent

    data class Completed(
        override val url: String,
        val result: DownloadResult
    ) : DownloadEvent

    data class Failed(
        override val url: String,
        val error: JmxError
    ) : DownloadEvent
}

fun interface DownloadObserver {
    fun onEvent(event: DownloadEvent)

    object None : DownloadObserver {
        override fun onEvent(event: DownloadEvent) = Unit
    }
}
