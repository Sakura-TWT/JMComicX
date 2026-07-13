package dev.jmx.client.core.download

data class DownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class DownloadResult(
    val url: String,
    val statusCode: Int,
    val contentType: String?,
    val contentLength: Long,
    val bytesWritten: Long
)
