package app.prismia.network

import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** An owned, bounded response. No open response body escapes the transport. */
typealias SourceHttpResponse = BufferedHttpResponse

/**
 * Shared mechanics only: protocol headers, authentication and retry decisions
 * remain with each source. OkHttp callbacks keep blocking body reads off caller
 * threads; cancellation owns the Call until the entire body has been consumed.
 */
class SourceHttpClient(
    client: OkHttpClient,
    private val source: ContentSource,
    private val maxResponseBytes: Long = 8L * 1024 * 1024,
    private val maxRedirects: Int = 5,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .build()

    private val executor = BufferedHttpExecutor(this.client, maxResponseBytes)

    init {
        require(maxResponseBytes in 1..Int.MAX_VALUE.toLong())
        require(maxRedirects in 0..10)
        require(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty()) {
            "source transport requires a client without interceptors; headers are owned by source adapters"
        }
    }

    suspend fun execute(request: Request, credentialScope: HttpUrl? = null): SourceHttpResponse {
        var current = request
        var redirects = 0
        while (true) {
            if (credentialScope == null || !current.url.isWithin(credentialScope)) {
                current = current.newBuilder()
                    .removeHeader("Authorization")
                    .removeHeader("Cookie")
                    .removeHeader("X-Site")
                    .build()
            }
            val response = exchange(current)
            // Never automatically replay credential-bearing POST bodies.
            if (current.method != "GET" || response.status !in REDIRECTS) return response
            val location = response.headers["Location"] ?: return response
            if (redirects++ >= maxRedirects) throw SourceRedirectException(source, "source redirect limit exceeded")
            val next = current.url.resolve(location)
                ?: throw SourceRedirectException(source, "source returned an invalid redirect")
            if (current.url.isHttps && !next.isHttps) {
                throw SourceRedirectException(source, "source attempted an HTTPS downgrade")
            }
            current = current.newBuilder().url(next).build()
        }
    }

    private suspend fun exchange(request: Request): SourceHttpResponse = try {
        executor.execute(request)
    } catch (failure: ResponseSizeLimitException) {
        throw SourceBodyLimitException(source, failure)
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** Exact origin and path boundary; string-prefix checks permit lookalike hosts. */
fun HttpUrl.isWithin(scope: HttpUrl): Boolean {
    if (scheme != scope.scheme || host != scope.host || port != scope.port) return false
    val path = scope.encodedPath.trimEnd('/')
    return encodedPath == path || encodedPath.startsWith("$path/")
}

class SourceBodyLimitException(source: ContentSource, cause: Throwable? = null) : IllegalStateException("source response exceeded the configured size limit", cause), SourceFailureCarrier {
    override val sourceFailure = SourceFailure(source, "transport.body", SourceErrorCategory.PROTOCOL, message = checkNotNull(message))
}

class SourceRedirectException(source: ContentSource, message: String) : IllegalStateException(message), SourceFailureCarrier {
    override val sourceFailure = SourceFailure(source, "transport.redirect", SourceErrorCategory.PROTOCOL, message = message)
}
