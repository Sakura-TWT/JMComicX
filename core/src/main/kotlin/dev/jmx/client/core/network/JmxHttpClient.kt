package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.HttpMethod
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CookieJar
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class JmxHttpClient(
    private val endpointManager: ApiEndpointManager,
    private val tokenProvider: ApiTokenProvider = ApiTokenProvider(),
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val retryPolicy: RetryPolicy = DefaultRetryPolicy()
) {
    suspend fun execute(request: ApiRequest): JmxResult<RawNetworkResponse> {
        var lastError: JmxError? = null
        repeat(retryPolicy.maxAttempts.coerceAtLeast(1)) { attempt ->
            val baseUrl = when (val current = endpointManager.current()) {
                is JmxResult.Success -> current.value
                is JmxResult.Failure -> return current
            }
            when (val result = executeOnce(baseUrl, request)) {
                is JmxResult.Success -> {
                    endpointManager.markSuccess(baseUrl)
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

    private suspend fun executeOnce(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<RawNetworkResponse> =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.create(apiRequest.route)
            val url = buildUrl(baseUrl, apiRequest).unwrapOrReturn { return@withContext it }
            val request = buildRequest(url, apiRequest, token.token, token.tokenParam)
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        return@withContext JmxResult.Failure(
                            JmxError.Http(
                                code = response.code,
                                message = "HTTP 请求失败：${response.code}",
                                retryable = response.code >= 500 || response.code == 408 || response.code == 429
                            )
                        )
                    }
                    JmxResult.Success(
                        RawNetworkResponse(
                            statusCode = response.code,
                            body = body,
                            contentType = response.body.contentType()?.toString(),
                            requestUrl = response.request.url.toString(),
                            tokenTimestampSeconds = token.timestampSeconds
                        )
                    )
                }
            }.getOrElse {
                JmxResult.Failure(it.toJmxNetworkError())
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
            .header("Accept-Encoding", "gzip, deflate")
            .header("user-agent", JmxProtocolConstants.MobileUserAgent)
            .header("token", token)
            .header("tokenparam", tokenParam)
        apiRequest.headers.forEach { (key, value) -> builder.header(key, value) }
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

fun defaultOkHttpClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()
}
