package app.prismia.source.iwara

import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import app.prismia.foundation.SourceRetryPolicy
import app.prismia.foundation.withSourceRetry
import app.prismia.network.SourceHttpClient
import app.prismia.network.SourceHttpResponse
import app.prismia.network.isWithin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.net.ssl.SSLException

interface IwaraTransport {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String

    suspend fun post(
        path: String,
        body: String,
        query: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
    ): String = throw UnsupportedOperationException("Iwara transport does not support POST")
}

class OkHttpIwaraTransport(
    client: OkHttpClient,
    baseUrl: String = "https://api.iwara.tv",
    private val userAgent: String = "Prismia/0.x (Android)",
    private val accessTokenProvider: suspend () -> String? = { null },
    private val retryPolicy: SourceRetryPolicy = SourceRetryPolicy(),
    private val rejectedTokenRefresher: (suspend (String) -> String?)? = null,
) : IwaraTransport {
    private val apiBase = (baseUrl.trimEnd('/') + "/").toHttpUrl()
    private val http = SourceHttpClient(client, ContentSource.IWARA)

    override suspend fun get(path: String, query: Map<String, String>): String {
        val target = resolve(path, query)
        val isApi = target.isWithin(apiBase)
        // CDN resolution cannot load credentials or trigger a refresh.
        val token = if (isApi) accessTokenProvider()?.takeIf(String::isNotBlank) else null
        val first = getResponse(target, token)
        if (first.status == 401 && isApi && first.finalUrl.isWithin(apiBase) &&
            first.headers["cf-mitigated"] != "challenge" && !isCloudflareChallenge(first.body.take(512)) &&
            token != null && rejectedTokenRefresher != null
        ) {
            val renewed = rejectedTokenRefresher.invoke(token)?.takeIf(String::isNotBlank)
            if (renewed != null && renewed != token) {
                return getResponse(target, renewed).requireSuccess("transport.GET")
            }
        }
        return first.requireSuccess("transport.GET")
    }

    override suspend fun post(
        path: String,
        body: String,
        query: Map<String, String>,
        bearerToken: String?,
    ): String {
        require(body.isNotBlank()) { "POST body must not be blank" }
        val target = resolve(path, query)
        require(target.isWithin(apiBase)) { "Iwara POST must target the API" }
        val request = request(target, bearerToken)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        // A POST is not retried or redirected implicitly.
        return http.execute(request, apiBase).requireSuccess("transport.POST")
    }

    private suspend fun getResponse(target: HttpUrl, token: String?): SourceHttpResponse = withSourceRetry(
        policy = retryPolicy,
        isRetryable = { failure ->
            (failure is IOException && failure !is SSLException) ||
                (failure is IwaraHttpException && failure.sourceFailure.retryable)
        },
    ) {
        val response = http.execute(request(target, token).get().build(), apiBase)
        if (response.status.isTransient()) response.requireSuccess("transport.GET")
        response
    }

    private fun request(url: HttpUrl, token: String?): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", userAgent)
        .header("Referer", "https://www.iwara.tv/")
        .apply {
            if (url.isWithin(apiBase)) {
                header("X-Site", "www.iwara.tv")
                token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }
        }

    private fun resolve(path: String, query: Map<String, String>): HttpUrl {
        val target = if (path.startsWith("https://") || path.startsWith("http://")) {
            path.toHttpUrl()
        } else {
            require(!path.contains("://") && !path.startsWith("//")) { "unsupported Iwara URL" }
            requireNotNull(apiBase.resolve(path.trimStart('/'))) { "invalid Iwara path" }
        }
        return target.newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
    }

    private fun SourceHttpResponse.requireSuccess(operation: String): String {
        if (status !in 200..299) {
            throw IwaraHttpException(status, body.take(512), operation, headers["cf-mitigated"] == "challenge")
        }
        return body
    }
}

class IwaraHttpException(
    val statusCode: Int,
    val responseBodyPreview: String,
    val operation: String = "transport.get",
    private val cloudflareChallenge: Boolean = false,
) : IllegalStateException("iwara HTTP $statusCode"), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() = SourceFailure(
            source = ContentSource.IWARA,
            operation = operation,
            category = when {
                cloudflareChallenge || isCloudflareChallenge(responseBodyPreview) -> SourceErrorCategory.CLOUDFLARE
                statusCode == 401 || statusCode == 403 -> SourceErrorCategory.AUTHENTICATION
                statusCode == 429 -> SourceErrorCategory.RATE_LIMITED
                else -> SourceErrorCategory.HTTP
            },
            httpStatus = statusCode,
            retryable = !cloudflareChallenge && !isCloudflareChallenge(responseBodyPreview) && statusCode.isTransient(),
            message = "iwara HTTP $statusCode",
            cause = this,
        )
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private fun Int.isTransient(): Boolean = this == 408 || this == 425 || this == 429 || this in 500..599
private fun isCloudflareChallenge(body: String): Boolean {
    val preview = body.lowercase()
    return "cf-mitigated" in preview || "just a moment" in preview || "challenge-platform" in preview
}
