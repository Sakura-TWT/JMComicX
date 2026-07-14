package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.HttpMethod
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.NetworkExchange
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
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
    private val bodySampler: BodySampler = BodySampler()
) {
    suspend fun probeAll(route: ApiRoute = ApiRoute.Setting): List<ApiEndpointProbeResult> {
        val endpoints = endpointManager.all().map { it.url }
        return endpoints.map { probe(it, route) }
    }

    suspend fun probe(url: HttpUrl, route: ApiRoute = ApiRoute.Setting): ApiEndpointProbeResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val token = tokenProvider.create(route)
            val requestUrl = url.newBuilder().encodedPath(route.path).build()
            val request = buildRequest(requestUrl, route, token.token, token.tokenParam)
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    val latencyMillis = elapsedMillisSince(startedAt)
                    val exchange = NetworkExchange(
                        route = route.path,
                        requestUrl = response.request.url.toString(),
                        statusCode = response.code,
                        contentType = response.body.contentType()?.toString(),
                        tokenTimestampSeconds = token.timestampSeconds,
                        bodySample = bodySampler.sample(body)
                    )
                    val error = probeErrorOrNull(route, response.code, response.isSuccessful, body, token.timestampSeconds, exchange)
                    if (error == null) {
                        endpointManager.markSuccess(url, latencyMillis)
                        ApiEndpointProbeResult(
                            url = url.toString(),
                            route = route.path,
                            success = true,
                            statusCode = response.code,
                            latencyMillis = latencyMillis,
                            exchange = exchange
                        )
                    } else {
                        endpointManager.markFailure(url, error.message)
                        ApiEndpointProbeResult(
                            url = url.toString(),
                            route = route.path,
                            success = false,
                            statusCode = response.code,
                            latencyMillis = latencyMillis,
                            error = error,
                            exchange = exchange
                        )
                    }
                }
            }.getOrElse {
                val latencyMillis = elapsedMillisSince(startedAt)
                val error = it.toJmxNetworkError()
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
            is SocketTimeoutException -> JmxError.Network("Network probe timeout", this)
            is UnknownHostException -> JmxError.Domain("API probe domain cannot resolve", cause = this)
            is IOException -> JmxError.Network("Network probe request failed", this)
            else -> JmxError.Unknown(message ?: "Unknown probe error", this)
        }
    }
}
