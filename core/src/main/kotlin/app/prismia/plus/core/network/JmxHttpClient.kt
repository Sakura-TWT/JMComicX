package app.prismia.plus.core.network

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val bodySampler: BodySampler = BodySampler(),
    private val requestMetricsRecorder: RequestMetricsRecorder? = null,
    private val queryLanguageProvider: () -> String? = { null }
) {
    private val nonReplayClient by lazy {
        okHttpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }

    fun withCookieJar(cookieJar: CookieJar): JmxHttpClient {
        return JmxHttpClient(
            endpointManager = endpointManager,
            tokenProvider = tokenProvider,
            okHttpClient = okHttpClient.newBuilder().cookieJar(cookieJar).build(),
            retryPolicy = retryPolicy,
            bodySampler = bodySampler,
            requestMetricsRecorder = requestMetricsRecorder,
            queryLanguageProvider = queryLanguageProvider
        )
    }

    suspend fun execute(request: ApiRequest): JmxResult<RawNetworkResponse> {
        val excludedEndpointUrl = request.excludedEndpointUrl?.toHttpUrlOrNull()
        val startedAtMillis = System.currentTimeMillis()
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
                            durationMillis = System.currentTimeMillis() - startedAtMillis,
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
                                durationMillis = System.currentTimeMillis() - startedAtMillis,
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
                durationMillis = System.currentTimeMillis() - startedAtMillis,
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

    /**
     * 单次出网。整段搬到 IO 线程上执行。
     *
     * [awaitResponse] 用的是 `enqueue` + [suspendCancellableCoroutine]：OkHttp 在自己的线程里
     * 完成传输，但协程是在**调用方的调度器**上恢复的。而调用方几乎都是 Compose 的
     * LaunchedEffect（[Dispatchers.Main.immediate]），于是响应一到，读 socket、解 gzip
     * （`body.string()`）、采样响应体、扫描风控页全都落在主线程上。
     * 这正是"加载图标转着转着，快要出内容时整个界面卡一下"的来源——每次卡顿都发生在
     * 加载即将完成的那一刻，因为那一刻主线程才刚开始干最重的活。
     *
     * 只包 [performRequest] 而不包整个 [execute]：重试退避的 [delay] 留在调用方调度器上，
     * 既省一次线程切换，也让测试里的虚拟时间继续管得住退避。
     */
    private suspend fun executeOnce(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<RawNetworkResponse> =
        withContext(Dispatchers.IO) { performRequest(baseUrl, apiRequest) }

    private suspend fun performRequest(baseUrl: HttpUrl, apiRequest: ApiRequest): JmxResult<RawNetworkResponse> {
        val token = tokenProvider.create(apiRequest.route)
        val url = buildUrl(baseUrl, apiRequest, token.timestampSeconds).unwrapOrReturn { return it }
        val request = buildRequest(url, apiRequest, token.token, token.tokenParam)
        return try {
            val client = if (apiRequest.route == app.prismia.plus.core.protocol.ApiRoute.FavoriteAction) nonReplayClient else okHttpClient
            client.newCall(request).awaitResponse().use { response ->
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
                                response.code == 403,
                            retryAfterMillis = response.retryAfterMillisOrNull()
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

    private fun buildUrl(
        baseUrl: HttpUrl,
        apiRequest: ApiRequest,
        timestampSeconds: Long
    ): JmxResult<HttpUrl> {
        return runCatching {
            buildApiUrl(baseUrl, apiRequest, timestampSeconds, queryLanguageProvider())
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

/**
 * `Retry-After` 支持两种写法：秒数（`Retry-After: 5`）与 HTTP-date。
 * 这里只解析秒数形式——接口用的是秒数，HTTP-date 形式还要处理客户端时钟偏移，
 * 解析不出来就当没有，交回给客户端自己的退避。
 */
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

private fun Response.retryAfterMillisOrNull(): Long? {
    val raw = header("Retry-After")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toLongOrNull()?.takeIf { it >= 0L }?.times(1_000L)
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        }
    )
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
