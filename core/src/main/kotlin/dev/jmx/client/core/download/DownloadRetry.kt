package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxError
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** One budget is shared by failover and retry; no outer page-level retry multiplier. */
internal object DownloadRetry {
    fun transient(error: JmxError): Boolean = error.retryable && when (error) {
        is JmxError.Network -> generateSequence(error.cause) { it.cause }.take(12)
            .none { it is SSLHandshakeException || it is SSLPeerUnverifiedException }
        is JmxError.Http -> error.code in setOf(408, 429, 500, 502, 503, 504) &&
            (error.retryAfterMillis ?: 0L) <= 5_000L
        else -> false
    }

    fun delayMillis(error: JmxError, attempt: Int): Long =
        (error as? JmxError.Http)?.retryAfterMillis?.coerceAtLeast(0L)
            ?: (250L shl (attempt - 1).coerceIn(0, 3))

    fun retryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim() ?: return null
        raw.toLongOrNull()?.let { return it.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L }
        // Too-large numeric values must stop retries, not overflow into an immediate retry.
        if (raw.isNotEmpty() && raw.all(Char::isDigit)) return Long.MAX_VALUE
        return runCatching {
            (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
                .coerceAtLeast(0L)
        }.getOrNull()
    }
}

/** Storage failures are not network failures, even if an output stream throws IOException. */
class DownloadSinkException(cause: Exception) : Exception("下载文件写入失败", cause)
