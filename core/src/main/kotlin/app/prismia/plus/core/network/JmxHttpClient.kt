package app.prismia.plus.core.network

import app.prismia.network.BufferedHttpExecutor
import app.prismia.network.ResponseSizeLimitException
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.HttpMethod
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.protocol.JmxServerMessages
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.NetworkExchange
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import okhttp3.CookieJar
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class JmxHttpClient(
    private val endpointManager: ApiEndpointManager,
    private val tokenProvider: ApiTokenProvider = ApiTokenProvider(),
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val retryPolicy: RetryPolicy = DefaultRetryPolicy(),
    private val bodySampler: BodySampler = BodySampler(),
    private val requestMetricsRecorder: RequestMetricsRecorder? = null,
    private val queryLanguageProvider: () -> String? = { null },
    private val maxResponseBytes: Long = 8L * 1024 * 1024,
) {
    private val nonReplayClient by lazy {
        okHttpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
    private val executor = BufferedHttpExecutor(okHttpClient, maxResponseBytes)
    private val nonReplayExecutor by lazy { BufferedHttpExecutor(nonReplayClient, maxResponseBytes) }

    fun withCookieJar(cookieJar: CookieJar): JmxHttpClient {
        return JmxHttpClient(
            endpointManager = endpointManager,
            tokenProvider = tokenProvider,
            okHttpClient = okHttpClient.newBuilder().cookieJar(cookieJar).build(),
            retryPolicy = retryPolicy,
            bodySampler = bodySampler,
            requestMetricsRecorder = requestMetricsRecorder,
            queryLanguageProvider = queryLanguageProvider,
            maxResponseBytes = maxResponseBytes,
        )
    }

    suspend fun execute(request: ApiRequest): JmxResult<RawNetworkResponse> = executePrepared(snapshotRequest(request))

    /** Freeze caller-owned maps and implicit language before any suspension or retry. */
    internal fun snapshotRequest(request: ApiRequest): ApiRequest = request.copy(
        query = buildMap {
            putAll(request.query)
            if (request.route.method == HttpMethod.Get && request.query["lang"] == null) {
                queryLanguageProvider()?.takeIf(String::isNotBlank)?.let { put("lang", it) }
            }
        },
        form = request.form.toMap(),
        headers = request.headers.toMap(),
    )

    internal suspend fun executePrepared(request: ApiRequest): JmxResult<RawNetworkResponse> {
        val excludedEndpointUrl = request.excludedEndpointUrl?.toHttpUrlOrNull()
        val startedAtNanos = System.nanoTime()
        var lastError: JmxError? = null
        var attempts = 0
        repeat(retryPolicy.maxAttempts.coerceAtLeast(1)) { attempt ->
            val baseUrl = when (val current = endpointManager.current(excludedUrl = excludedEndpointUrl)) {
                is JmxResult.Success -> current.value
                is JmxResult.Failure -> return current
            }
            attempts = attempt + 1
            val startedAt = System.nanoTime()
            when (val result = executeOnce(baseUrl, request)) {
                is JmxResult.Success -> {
                    endpointManager.markSuccess(baseUrl, elapsedMillisSince(startedAt))
                    requestMetricsRecorder?.record(
                        RequestMetricRecord(
                            route = request.route.path,
                            endpointHost = baseUrl.host,
                            durationMillis = elapsedMillisSince(startedAtNanos),
                            attempts = attempts,
                            success = true
                        )
                    )
                    return result
                }
                is JmxResult.Failure -> {
                    lastError = result.error
                    val decision = retryPolicy.decide(result.error, attempt)
                    if (decision.shouldFailover) {
                        endpointManager.markFailure(baseUrl, result.error.message)
                    }
                    // 收藏是 toggle：响应丢失时重放会取消刚刚加入的收藏。
                    // 不在传输层重试，由调用方重新读取真实收藏态后决定下一步。
                    if (!decision.shouldRetry || request.route == app.prismia.plus.core.protocol.ApiRoute.FavoriteAction) {
                        requestMetricsRecorder?.record(
                            RequestMetricRecord(
                                route = request.route.path,
                                endpointHost = baseUrl.host,
                                durationMillis = elapsedMillisSince(startedAtNanos),
                                attempts = attempts,
                                success = false,
                                errorKind = result.error.javaClass.simpleName
                            )
                        )
                        return result
                    }
                    // 退避由 RetryPolicy 决定：换端点时为 0（立即换机重试），
                    // 同端点重试才等待，避免原地立刻重发撞上同一个瞬时故障或放大限流。
                    if (decision.delayMillis > 0L) delay(decision.delayMillis)
                }
            }
        }
        requestMetricsRecorder?.record(
            RequestMetricRecord(
                route = request.route.path,
                endpointHost = "",
                durationMillis = elapsedMillisSince(startedAtNanos),
                attempts = attempts,
                success = false,
                errorKind = lastError?.javaClass?.simpleName
            )
        )
        return JmxResult.Failure(lastError ?: JmxError.Network("请求失败，未获得有效响应"))
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
    }

    /** Body IO belongs to BufferedHttpExecutor; protocol inspection remains off the caller's UI thread. */
    private suspend fun executeOnce(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<RawNetworkResponse> =
        withContext(Dispatchers.IO) { performRequest(baseUrl, apiRequest) }

    private suspend fun performRequest(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<RawNetworkResponse> {
        val token = tokenProvider.create(apiRequest.route)
        val url = buildUrl(baseUrl, apiRequest, token.timestampSeconds).unwrapOrReturn { return it }
        val request = buildRequest(url, apiRequest, token.token, token.tokenParam)
        return try {
            val transport = if (apiRequest.route == app.prismia.plus.core.protocol.ApiRoute.FavoriteAction) nonReplayExecutor else executor
            transport.execute(request).let { response ->
                val body = response.body
                val contentType = response.headers["Content-Type"]
                val exchange = NetworkExchange(
                    route = apiRequest.route.path,
                    requestUrl = response.finalUrl.toString(),
                    statusCode = response.status,
                    contentType = contentType,
                    tokenTimestampSeconds = token.timestampSeconds,
                    bodySample = bodySampler.sample(body)
                )
                if (response.status !in 200..299) {
                    return JmxResult.Failure(
                        JmxError.Http(
                            code = response.status,
                            message = JmxServerMessages.composeHttpFailureMessage(response.status, body),
                            exchange = exchange,
                            retryable = response.status >= 500 ||
                                response.status == 408 ||
                                response.status == 429 ||
                                response.status == 403,
                            retryAfterMillis = response.headers.retryAfterMillisOrNull()
                        )
                    )
                }
                when (val inspection = ResponseBodyInspector.inspect(apiRequest.route, response.status, body)) {
                    is JmxResult.Failure -> return JmxResult.Failure(inspection.error.withExchange(exchange))
                    is JmxResult.Success -> Unit
                }
                JmxResult.Success(
                    RawNetworkResponse(
                        statusCode = response.status,
                        body = body,
                        contentType = contentType,
                        requestUrl = response.finalUrl.toString(),
                        tokenTimestampSeconds = token.timestampSeconds
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            JmxResult.Failure(error.toJmxNetworkError())
        }
    }

    private fun buildUrl(
        baseUrl: HttpUrl,
        apiRequest: ApiRequest,
        timestampSeconds: Long
    ): JmxResult<HttpUrl> {
        return runCatching {
            buildApiUrl(baseUrl, apiRequest, timestampSeconds, queryLanguage = null)
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
            is ResponseSizeLimitException -> JmxError.Schema("API 响应超过大小限制", field = "responseBody", cause = this)
            is SocketTimeoutException -> JmxError.Network("网络连接超时", this)
            is UnknownHostException -> JmxError.Domain("API 域名无法解析", cause = this)
            is IOException -> JmxError.Network("网络请求失败", this)
            else -> JmxError.Unknown(message ?: "未知网络错误", this)
        }
    }
}

/**
 * 按路由与参数拼出接口请求 URL。
 *
 * 抽成顶层函数是为了让 [ApiEndpointProber] 复用同一份逻辑：探测请求必须与真实请求同构
 * （同样带 lang、同样带破缓存的 t），否则探测走的是另一条 URL，
 * "探测通过"就不能代表真实请求也通得过——尤其 lang 参数会影响服务端的分流与缓存命中。
 *
 * @param queryLanguage 内容语言；null/空白表示不附加 lang，保持服务端默认（简体）。
 */
internal fun buildApiUrl(
    baseUrl: HttpUrl,
    apiRequest: ApiRequest,
    timestampSeconds: Long,
    queryLanguage: String?
): HttpUrl {
    val builder = baseUrl.newBuilder().encodedPath(apiRequest.route.path)
    apiRequest.query.forEach { (key, value) ->
        if (value != null) builder.addQueryParameter(key, value)
    }
    // 官方客户端为所有 GET 附加 lang 查询参数控制内容简繁；
    // 未设置语言偏好时不附加，保持服务端默认（简体）
    if (apiRequest.route.method == HttpMethod.Get && apiRequest.query["lang"] == null) {
        queryLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
            builder.addQueryParameter("lang", lang)
        }
    }
    // 破中间层缓存（见 ApiRoute.cacheBuster）。刻意只在这里追加而不写进 query：
    // 进了 query 就会进去重键与本地响应缓存键，两者都会被每秒一变的时间戳打废。
    if (apiRequest.route.cacheBuster && apiRequest.query["t"] == null) {
        builder.addQueryParameter("t", timestampSeconds.toString())
    }
    return builder.build()
}

/**
 * Parse the delta-seconds form of Retry-After with saturating conversion.
 * HTTP-date and malformed values fall back to the protocol retry policy.
 */
internal fun Headers.retryAfterMillisOrNull(): Long? {
    val seconds = get("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
}

/**
 * 共享的基础客户端（连接池 / 分发器 / DNS 缓存都在这一层复用）。
 *
 * 超时值对齐官方客户端的 15s：原先三项都是 30s，一台不可达的主机要卡满 30s 才会换下一台，
 * 三次尝试就是 90s 白屏。连接超时单独收到 8s——连不上通常几秒内就能判定，
 * 继续等只是在推迟换端点。图片下载走同一个基础客户端，读超时不能再往下压。
 */
fun defaultOkHttpClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()
}

/**
 * 接口专用客户端：在 [base] 之上加一个整次调用的总时限。
 *
 * 为什么派生而不是直接设在基础客户端上：callTimeout 覆盖 DNS + 连接 + 写 + 读的总和，
 * 对一张几 MB 的漫画图来说 20s 太短，弱网下会把正常下载切断；
 * 而接口响应都是几十 KB 级，超过 20s 基本可以判定这条链路没救了，
 * 早点失败换端点比继续挂着更快。newBuilder() 保留同一个连接池与分发器，不额外占资源。
 */
fun apiOkHttpClient(base: OkHttpClient): OkHttpClient {
    return base.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}
