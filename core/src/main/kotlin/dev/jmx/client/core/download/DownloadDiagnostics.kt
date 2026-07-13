package dev.jmx.client.core.download

data class DownloadFailureContext(
    val url: String,
    val statusCode: Int? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val bytesRead: Long? = null,
    val maxBytes: Long? = null
) {
    fun describe(): String {
        return buildList {
            add("url=$url")
            statusCode?.let { add("status=$it") }
            contentType?.let { add("contentType=$it") }
            contentLength?.let { add("contentLength=$it") }
            bytesRead?.let { add("bytesRead=$it") }
            maxBytes?.let { add("maxBytes=$it") }
        }.joinToString(", ")
    }
}
