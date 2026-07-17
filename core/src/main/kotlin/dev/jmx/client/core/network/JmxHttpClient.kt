package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.HttpMethod
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.protocol.JmxServerMessages
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.NetworkExchange
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.CookieJar
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class JmxHttpClient(
    private val endpointManager: ApiEndpointManager,
    private val tokenProvider: ApiTokenProvider = ApiTokenProvider(),
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val retryPolicy: RetryPolicy = DefaultRetryPolicy(),
    private val bodySampler: BodySampler = BodySampler()
) {
    fun withCookieJar(cookieJar: CookieJar): JmxHttpClient {
        return JmxHttpClient(
            endpointManager = endpointManager,
            tokenProvider = tokenProvider,
            okHttpClient = okHttpClient.newBuilder().cookieJar(cookieJar).build(),
            retryPolicy = retryPolicy,
            bodySampler = bodySampler
        )
    }

    suspend fun execute(request: ApiRequest): JmxResult<RawNetworkResponse> {
        var lastError: JmxError? = null
        repeat(retryPolicy.maxAttempts.coerceAtLeast(1)) { attempt ->
            val baseUrl = when (val current = endpointManager.current()) {
                is JmxResult.Success -> current.value
                is JmxResult.Failure -> return current
            }
            val startedAt = System.nanoTime()
            when (val result = executeOnce(baseUrl, request)) {
                is JmxResult.Success -> {
                    endpointManager.markSuccess(baseUrl, elapsedMillisSince(startedAt))
                    return result
                }
                is JmxResult.Failure -> {
                    lastError = result.error
                    val decision = retryPolicy.decide(result.error, attempt)
                    if (decision.shouldFailover) {
                        endpointManager.markFailure(baseUrl, result.error.message)
                    }
                    if (!decision.shouldRetry) return result
                }
            }
        }
        return JmxResult.Failure(lastError ?: JmxError.Network("请求失败，未获得有效响应"))
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
    }

    private suspend fun executeOnce(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<RawNetworkResponse> {
        val token = tokenProvider.create(apiRequest.route)
        val url = buildUrl(baseUrl, apiRequest).unwrapOrReturn { return it }
        val request = buildRequest(url, apiRequest, token.token, token.tokenParam)
        return try {
            okHttpClient.newCall(request).awaitResponse().use { response ->
                val body = response.body.string()
                val contentType = response.body.contentType()?.toString()
                val exchange = NetworkExchange(
                    route = apiRequest.route.path,
                    requestUrl = response.request.url.toString(),
                    statusCode = response.code,
                    contentType = contentType,
                    tokenTimestampSeconds = token.timestampSeconds,
                    bodySample = bodySampler.sample(body)
                )
                if (!response.isSuccessful) {
                    return JmxResult.Failure(
                        JmxError.Http(
                            code = response.code,
                            message = JmxServerMessages.composeHttpFailureMessage(response.code, body),
                            exchange = exchange,
                            retryable = response.code >= 500 ||
                                response.code == 408 ||
                                response.code == 429 ||
                                response.code == 403
                        )
                    )
                }
                when (val inspection = ResponseBodyInspector.inspect(apiRequest.route, response.code, body)) {
                    is JmxResult.Failure -> return JmxResult.Failure(inspection.error.withExchange(exchange))
                    is JmxResult.Success -> Unit
                }
                JmxResult.Success(
                    RawNetworkResponse(
                        statusCode = response.code,
                        body = body,
                        contentType = contentType,
                        requestUrl = response.request.url.toString(),
                        tokenTimestampSeconds = token.timestampSeconds
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            JmxResult.Failure(error.toJmxNetworkError())
        }
    }

    private fun buildUrl(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<HttpUrl> {
        return runCatching {
            val builder = baseUrl.newBuilder().encodedPath(apiRequest.route.path)
            apiRequest.query.forEach { (key, value) ->
                if (value != null) builder.addQueryParameter(key, value)
            }
            builder.build()
        }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Schema("请求 URL 构建失败：${apiRequest.route.path}", cause = it)) }
        )
    }

    private fun buildRequest(url: HttpUrl, apiRequest: ApiRequest, token: String, tokenParam: String): Request {
        val builder = Request.Builder()
            .url(url)

            .header("user-agent", JmxProtocolConstants.MobileUserAgent)
            .header("token", token)
            .header("tokenparam", tokenParam)
        apiRequest.headers.forEach { (key, value) ->
            if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        return when (apiRequest.route.method) {
            HttpMethod.Get -> builder.get().build()
            HttpMethod.Post -> {
                val form = FormBody.Builder().apply {
                    apiRequest.form.forEach { (key, value) ->
                        if (value != null) add(key, value)
                    }
                }.build()
                builder.post(form).build()
            }
        }
    }

    private inline fun <T> JmxResult<T>.unwrapOrReturn(returnFailure: (JmxResult.Failure) -> Nothing): T {
        return when (this) {
            is JmxResult.Success -> value
            is JmxResult.Failure -> returnFailure(this)
        }
    }

    private fun Throwable.toJmxNetworkError(): JmxError {
        return when (this) {
            is SocketTimeoutException -> JmxError.Network("网络连接超时", this)
            is UnknownHostException -> JmxError.Domain("API 域名无法解析", cause = this)
            is IOException -> JmxError.Network("网络请求失败", this)
            else -> JmxError.Unknown(message ?: "未知网络错误", this)
        }
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        }
    )
}

fun defaultOkHttpClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()
}
