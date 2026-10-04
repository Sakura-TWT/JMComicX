package app.prismia.source.oreno

import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import app.prismia.foundation.SourceRetryPolicy
import app.prismia.foundation.withSourceRetry
import app.prismia.network.SourceHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.net.ssl.SSLException

interface OrenoTransport {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String
}

class OkHttpOrenoTransport(
    client: OkHttpClient,
    baseUrl: String,
    private val userAgent: String = "Prismia/0.x (Android)",
    private val retryPolicy: SourceRetryPolicy = SourceRetryPolicy(),
) : OrenoTransport {
    private val base = (baseUrl.trimEnd('/') + "/").toHttpUrl()
    private val http = SourceHttpClient(client, ContentSource.ORENO3D)

    override suspend fun get(path: String, query: Map<String, String>): String {
        require(!path.contains("://") && !path.startsWith("//")) { "Oreno expects a relative path" }
        val target = requireNotNull(base.resolve(path.trimStart('/'))).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(target)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", userAgent)
            .header("Referer", base.toString())
            .build()
        return withSourceRetry(
            policy = retryPolicy,
            isRetryable = { failure ->
                (failure is IOException && failure !is SSLException) ||
                    (failure is OrenoHttpException && failure.sourceFailure.retryable)
            },
        ) {
            val response = http.execute(request)
            if (response.status !in 200..299) {
                throw OrenoHttpException(response.status, response.body.take(512), response.headers["cf-mitigated"] == "challenge")
            }
            response.body
        }
    }
}

class OrenoHttpException(
    val statusCode: Int,
    val responseBodyPreview: String,
    private val cloudflareChallenge: Boolean = false,
) : IllegalStateException("oreno3d HTTP $statusCode"), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() {
            val challenge = cloudflareChallenge || "just a moment" in responseBodyPreview.lowercase() ||
                "challenge-platform" in responseBodyPreview.lowercase()
            return SourceFailure(
                source = ContentSource.ORENO3D,
                operation = "transport.get",
                category = when {
                    challenge -> SourceErrorCategory.CLOUDFLARE
                    statusCode == 401 || statusCode == 403 -> SourceErrorCategory.AUTHENTICATION
                    statusCode == 429 -> SourceErrorCategory.RATE_LIMITED
                    else -> SourceErrorCategory.HTTP
                },
                httpStatus = statusCode,
                retryable = !challenge && (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599),
                message = "oreno3d HTTP $statusCode",
                cause = this,
            )
        }
}
