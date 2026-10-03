package app.prismia.source.iwara

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface IwaraTransport {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String

    /**
     * Sends a JSON request to the Iwara API. The bearer argument is explicit
     * so refresh tokens never have to be exposed through a generic token
     * provider (and can never accidentally be sent to a CDN URL).
     */
    suspend fun post(
        path: String,
        body: String,
        query: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
    ): String = throw UnsupportedOperationException("Iwara transport does not support POST")
}

class OkHttpIwaraTransport(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.iwara.tv",
    private val userAgent: String = "Prismia/0.x (Android)",
    private val accessTokenProvider: suspend () -> String? = { null },
    private val retryPolicy: SourceRetryPolicy = SourceRetryPolicy(),
) : IwaraTransport {
    override suspend fun get(path: String, query: Map<String, String>): String = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()?.takeIf(String::isNotBlank)
        execute(
            path = path,
            query = query,
            method = "GET",
            body = null,
            bearerToken = token,
        )
    }

    override suspend fun post(
        path: String,
        body: String,
        query: Map<String, String>,
        bearerToken: String?,
    ): String = withContext(Dispatchers.IO) {
        require(body.isNotBlank()) { "POST body must not be blank" }
        execute(
            path = path,
            query = query,
            method = "POST",
            body = body,
            bearerToken = bearerToken?.takeIf(String::isNotBlank),
        )
    }

    private suspend fun execute(
        path: String,
        query: Map<String, String>,
        method: String,
        body: String?,
        bearerToken: String?,
    ): String {
        val queryString = query.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
        val isAbsoluteUrl = path.startsWith("https://") || path.startsWith("http://")
        val base = if (isAbsoluteUrl) path else baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val url = base + queryString.takeIf(String::isNotEmpty)?.let {
            if (base.contains('?')) "&$it" else "?$it"
        }.orEmpty()
        val apiRequest = !isAbsoluteUrl || path.startsWith(baseUrl.trimEnd('/'))
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.iwara.tv/")
        if (body != null) {
            requestBuilder
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.get()
        }
        if (apiRequest) {
            requestBuilder.header("X-Site", "www.iwara.tv")
            bearerToken?.let { requestBuilder.header("Authorization", "Bearer $it") }
        }
        val request = requestBuilder.build()
        return withSourceRetry(
            policy = retryPolicy,
            isRetryable = { failure ->
                failure is IOException || failure is IwaraHttpException && failure.statusCode.isTransient()
            },
        ) {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    throw IwaraHttpException(
                        statusCode = response.code,
                        responseBodyPreview = responseBody.take(MAX_ERROR_BODY_LENGTH),
                        operation = "transport.$method",
                    )
                }
                responseBody
            }
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

class IwaraHttpException(
    val statusCode: Int,
    val responseBodyPreview: String,
    val operation: String = "transport.get",
) : IllegalStateException("iwara HTTP $statusCode"), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() = SourceFailure(
            source = ContentSource.IWARA,
            operation = operation,
            category = when {
                isCloudflareChallenge(responseBodyPreview) -> SourceErrorCategory.CLOUDFLARE
                statusCode == 401 || statusCode == 403 -> SourceErrorCategory.AUTHENTICATION
                statusCode == 429 -> SourceErrorCategory.RATE_LIMITED
                else -> SourceErrorCategory.HTTP
            },
            httpStatus = statusCode,
            retryable = statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599,
            message = "iwara HTTP $statusCode",
            cause = this,
        )
}

private const val MAX_ERROR_BODY_LENGTH = 512
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun Int.isTransient(): Boolean = this == 408 || this == 425 || this == 429 || this in 500..599

private fun isCloudflareChallenge(body: String): Boolean {
    val preview = body.lowercase()
    return "cf-mitigated" in preview || "just a moment" in preview || "challenge-platform" in preview
}
