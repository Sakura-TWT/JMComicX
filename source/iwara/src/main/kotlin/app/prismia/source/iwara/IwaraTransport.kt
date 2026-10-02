package app.prismia.source.iwara

import app.prismia.foundation.SourceRetryPolicy
import app.prismia.foundation.withSourceRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

interface IwaraTransport {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String
}

class OkHttpIwaraTransport(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.iwara.tv",
    private val userAgent: String = "Prismia/0.x (Android)",
    private val accessTokenProvider: () -> String? = { null },
    private val retryPolicy: SourceRetryPolicy = SourceRetryPolicy(),
) : IwaraTransport {
    override suspend fun get(path: String, query: Map<String, String>): String = withContext(Dispatchers.IO) {
        val queryString = query.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
        val isAbsoluteUrl = path.startsWith("https://") || path.startsWith("http://")
        val base = if (isAbsoluteUrl) {
            path
        } else {
            baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
        val url = base + queryString.takeIf(String::isNotEmpty)?.let {
            if (base.contains('?')) "&$it" else "?$it"
        }.orEmpty()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.iwara.tv/")
        if (!isAbsoluteUrl || path.startsWith(baseUrl.trimEnd('/'))) {
            requestBuilder.header("X-Site", "www.iwara.tv")
            accessTokenProvider()?.takeIf(String::isNotBlank)?.let {
                requestBuilder.header("Authorization", "Bearer $it")
            }
        }
        val request = requestBuilder.build()
        withSourceRetry(
            policy = retryPolicy,
            isRetryable = { failure ->
                failure is IOException || failure is IwaraHttpException && failure.statusCode.isTransient()
            },
        ) {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw IwaraHttpException(response.code, body.take(MAX_ERROR_BODY_LENGTH))
                }
                body
            }
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

class IwaraHttpException(
    val statusCode: Int,
    val responseBodyPreview: String,
) : IllegalStateException("iwara HTTP $statusCode")

private const val MAX_ERROR_BODY_LENGTH = 512

private fun Int.isTransient(): Boolean = this == 408 || this == 425 || this == 429 || this in 500..599
