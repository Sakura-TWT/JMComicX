package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxResult

data class DownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val acceptedContentTypes: Set<String> = emptySet(),
    val maxBytes: Long? = null
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
