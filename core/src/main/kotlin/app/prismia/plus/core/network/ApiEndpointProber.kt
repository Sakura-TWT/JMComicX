package app.prismia.plus.core.network

import app.prismia.network.BufferedHttpExecutor
import app.prismia.network.ResponseSizeLimitException
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.HttpMethod
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.NetworkExchange
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class ApiEndpointProbeResult(
    val url: String,
    val route: String,
    val success: Boolean,
    val statusCode: Int?,
    val latencyMillis: Long,
    val error: JmxError? = null,
    val exchange: NetworkExchange? = null
)

class ApiEndpointProber(
    private val endpointManager: ApiEndpointManager,
    private val tokenProvider: ApiTokenProvider = ApiTokenProvider(),
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val responseDecoder: ApiResponseDecoder = ApiResponseDecoder(),
    private val bodySampler: BodySampler = BodySampler(),
    /**
     * 内容语言，需与 [JmxHttpClient] 用的是同一个来源。探测请求要和真实请求同构，
     * 否则探测出来的"这台机器可用"说的是另一条 URL 的事。
     */
    private val queryLanguageProvider: () -> String? = { null },
    maxResponseBytes: Long = 8L * 1024 * 1024,
) {
    private val executor = BufferedHttpExecutor(okHttpClient, maxResponseBytes)
    suspend fun probeAll(route: ApiRoute = ApiRoute.Setting): List<ApiEndpointProbeResult> {
        val endpoints = endpointManager.all().map { it.url }
        return coroutineScope {
            endpoints.map { endpoint -> async { probe(endpoint, route) } }.awaitAll()
        }
    }

    suspend fun probe(url: HttpUrl, route: ApiRoute = ApiRoute.Setting): ApiEndpointProbeResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val token = tokenProvider.create(route)
            val requestUrl = buildApiUrl(
                baseUrl = url,
                apiRequest = apiRequest(route),
                timestampSeconds = token.timestampSeconds,
                queryLanguage = queryLanguageProvider()
            )
            val request = buildRequest(requestUrl, route, token.token, token.tokenParam)
            try {
                executor.execute(request).let { response ->
                    val body = response.body
                    val latencyMillis = elapsedMillisSince(startedAt)
                    val exchange = NetworkExchange(
                        route = route.path,
                        requestUrl = response.finalUrl.toString(),
                        statusCode = response.status,
                        contentType = response.headers["Content-Type"],
                        tokenTimestampSeconds = token.timestampSeconds,
                        bodySample = bodySampler.sample(body)
                    )
                    val error = probeErrorOrNull(route, response.status, response.status in 200..299, body, token.timestampSeconds, exchange)
                    currentCoroutineContext().ensureActive()
                    if (error == null) {
                        endpointManager.markSuccess(url, latencyMillis)
                        ApiEndpointProbeResult(
                            url = url.toString(),
                            route = route.path,
                            success = true,
                            statusCode = response.status,
                            latencyMillis = latencyMillis,
                            exchange = exchange
                        )
                    } else {
                        endpointManager.markFailure(url, error.message)
                        ApiEndpointProbeResult(
                            url = url.toString(),
                            route = route.path,
                            success = false,
                            statusCode = response.status,
                            latencyMillis = latencyMillis,
                            error = error,
                            exchange = exchange
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val latencyMillis = elapsedMillisSince(startedAt)
                val error = failure.toJmxNetworkError()
                endpointManager.markFailure(url, error.message)
                ApiEndpointProbeResult(
                    url = url.toString(),
                    route = route.path,
                    success = false,
                    statusCode = null,
                    latencyMillis = latencyMillis,
                    error = error
                )
            }
        }

    private fun buildRequest(url: HttpUrl, route: ApiRoute, token: String, tokenParam: String): Request {
        val builder = Request.Builder()
            .url(url)

            .header("user-agent", JmxProtocolConstants.MobileUserAgent)
            .header("token", token)
            .header("tokenparam", tokenParam)
        return when (route.method) {
            HttpMethod.Get -> builder.get().build()
            HttpMethod.Post -> builder.post(FormBody.Builder().build()).build()
        }
    }

    private fun probeErrorOrNull(
        route: ApiRoute,
        statusCode: Int,
        isSuccessful: Boolean,
        body: String,
        tokenTimestampSeconds: Long,
        exchange: NetworkExchange
    ): JmxError? {
        if (!isSuccessful) {
            return JmxError.Http(
                code = statusCode,
                message = "HTTP probe failed: $statusCode",
                exchange = exchange
            )
        }
        if (!route.encryptedJson) return null
        val envelope = when (val decoded = responseDecoder.decodeEncryptedEnvelope(body, tokenTimestampSeconds)) {
            is JmxResult.Success -> decoded.value
            is JmxResult.Failure -> return decoded.error.withExchange(exchange)
        }
        return if (envelope.code == 200) {
            null
        } else {
            JmxError.Api(
                code = envelope.code,
                message = envelope.errorMessage ?: "API probe failed: ${envelope.code}",
                exchange = exchange
            )
        }
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
    }

    private fun Throwable.toJmxNetworkError(): JmxError {
        return when (this) {
            is ResponseSizeLimitException -> JmxError.Schema("Probe response exceeded the byte limit", field = "responseBody", cause = this)
            is SocketTimeoutException -> JmxError.Network("Network probe timeout", this)
            is UnknownHostException -> JmxError.Domain("API probe domain cannot resolve", cause = this)
            is IOException -> JmxError.Network("Network probe request failed", this)
            else -> JmxError.Unknown(message ?: "Unknown probe error", this)
        }
    }
}
