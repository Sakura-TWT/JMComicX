package app.prismia.source.oreno

import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import app.prismia.foundation.SourceRetryPolicy
import app.prismia.foundation.withSourceRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

interface OrenoTransport {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String
}

class OkHttpOrenoTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val userAgent: String = "Prismia/0.x (Android)",
    private val retryPolicy: SourceRetryPolicy = SourceRetryPolicy(),
) : OrenoTransport {
    override suspend fun get(path: String, query: Map<String, String>): String = withContext(Dispatchers.IO) {
        val queryString = query.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
        val url = baseUrl.trimEnd('/') + "/" + path.trimStart('/') +
            queryString.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", userAgent)
            .header("Referer", "https://oreno3d.com/")
            .build()
        withSourceRetry(
            policy = retryPolicy,
            isRetryable = { failure ->
                failure is IOException || failure is OrenoHttpException && failure.statusCode.isTransient()
            },
        ) {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw OrenoHttpException(response.code, body.take(MAX_ERROR_BODY_LENGTH))
                }
                body
            }
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

class OrenoHttpException(
    val statusCode: Int,
    val responseBodyPreview: String,
) : IllegalStateException("oreno3d HTTP $statusCode"), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() = SourceFailure(
            source = ContentSource.ORENO3D,
            operation = "transport.get",
            category = when (statusCode) {
                401, 403 -> SourceErrorCategory.AUTHENTICATION
                429 -> SourceErrorCategory.RATE_LIMITED
                else -> SourceErrorCategory.HTTP
            },
            httpStatus = statusCode,
            retryable = statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599,
            message = "oreno3d HTTP $statusCode",
            cause = this,
        )
}

private const val MAX_ERROR_BODY_LENGTH = 512

private fun Int.isTransient(): Boolean = this == 408 || this == 425 || this == 429 || this in 500..599
