package app.prismia.plus.core.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import app.prismia.plus.core.cache.JsonResponseCache
import app.prismia.plus.core.result.NetworkExchange
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JmxApiClient(
    private val httpClient: JmxHttpClient,
    private val responseDecoder: ApiResponseDecoder = ApiResponseDecoder(),
    private val bodySampler: BodySampler = BodySampler(),
    private val deduplicateInFlightRequests: Boolean = true,
    private val responseCache: JsonResponseCache? = null,
    private val cachePolicy: ResponseCachePolicy? = null,
    private val revalidationScope: CoroutineScope? = null,
    /**
     * 缓存键前缀。内容语言由 [JmxHttpClient] 在出网前追加为 lang 查询参数，
     * 因此不在 [dedupKey] 里，必须靠这里区分，否则简繁两种内容会互相串缓存。
     */
    private val cacheNamespace: () -> String = { "" },
    private val cacheGeneration: () -> String = { "" },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<JmxResult<JsonElement>>>()
    private val deduplicatedRequests = AtomicInteger(0)
    private val cacheHits = AtomicInteger(0)
    private val staleServedRequests = AtomicInteger(0)

    /** 因并发去重而免于重复发出的请求数，用于评估去重收益 */
    val deduplicatedRequestCount: Int get() = deduplicatedRequests.get()

    /** 命中磁盘缓存（新鲜或过期可用）而免于阻塞出网的请求数 */
    val cacheHitCount: Int get() = cacheHits.get()

    /** 先返回过期内容、再后台校验的请求数 */
    val staleServedCount: Int get() = staleServedRequests.get()

    fun withCookieJar(cookieJar: CookieJar): JmxApiClient {
        return JmxApiClient(
            httpClient = httpClient.withCookieJar(cookieJar),
            responseDecoder = responseDecoder,
            bodySampler = bodySampler,
            deduplicateInFlightRequests = deduplicateInFlightRequests,
            responseCache = responseCache,
            cachePolicy = cachePolicy,
            revalidationScope = revalidationScope,
            cacheNamespace = cacheNamespace,
            cacheGeneration = cacheGeneration,
            nowMillis = nowMillis
        )
    }

    suspend fun requestJson(request: ApiRequest): JmxResult<JsonElement> {
        val cache = responseCache
        val rule = cachePolicy?.ruleFor(request)
        if (cache == null || rule == null) return requestJsonDeduplicated(request)

        val key = cacheKey(request)
        val cached = readCache(cache, key)
        if (cached != null) {
            val (age, parsed) = cached
            if (age < rule.freshMillis) {
                cacheHits.incrementAndGet()
                return JmxResult.Success(parsed)
            }
            if (age < rule.staleMillis) {
                cacheHits.incrementAndGet()
                staleServedRequests.incrementAndGet()
                // 先出旧内容再后台校验：去重表保证同一请求不会因此发出两份。
                revalidationScope?.launch { revalidate(request, key) }
                return JmxResult.Success(parsed)
            }
        }

        val result = requestJsonDeduplicated(request)
        if (result is JmxResult.Success) writeCache(cache, key, result.value)
        return result
    }

    /**
     * 读缓存并解析，一次线程切换里做完。
     *
     * 读要碰磁盘、解析要走一遍 Gson，调用方却是 Compose 的 LaunchedEffect（主线程）。
     * 返回 age 而不是 Entry，是为了让 [nowMillis] 与解析结果在同一次快照里取齐。
     */
    private suspend fun readCache(cache: JsonResponseCache, key: String): Pair<Long, JsonElement>? =
        withContext(Dispatchers.IO) {
            val entry = cache.read(key) ?: return@withContext null
            if (entry.generation != cacheGeneration()) return@withContext null
            val parsed = entry.json.parseJsonOrNull() ?: return@withContext null
            entry.ageMillis(nowMillis()) to parsed
        }

    /** 写缓存：`toString()` 要把整棵树重新序列化（首页一次几十上百 KB），加上落盘，同样不能留在主线程。 */
    private suspend fun writeCache(cache: JsonResponseCache, key: String, value: JsonElement) {
        withContext(Dispatchers.IO) { cache.write(key, value.toString(), cacheGeneration()) }
    }

    private suspend fun revalidate(request: ApiRequest, key: String) {
        val cache = responseCache ?: return
        when (val result = requestJsonDeduplicated(request)) {
            is JmxResult.Success -> writeCache(cache, key, result.value)
            // 后台校验失败不做任何事：旧内容继续可用，下次前台请求再决定要不要报错。
            is JmxResult.Failure -> Unit
        }
    }

    private fun cacheKey(request: ApiRequest): String = "${cacheNamespace()}|${request.dedupKey()}"

    private suspend fun requestJsonDeduplicated(request: ApiRequest): JmxResult<JsonElement> {
        if (!deduplicateInFlightRequests) return requestJsonDirect(request)
        val key = request.dedupKey()
        while (true) {
            inFlightRequests[key]?.let {
                deduplicatedRequests.incrementAndGet()
                return it.await()
            }
            val mine = CompletableDeferred<JmxResult<JsonElement>>()
            val existing = inFlightRequests.putIfAbsent(key, mine)
            if (existing != null) {
                deduplicatedRequests.incrementAndGet()
                return existing.await()
            }
            try {
                val result = requestJsonDirect(request)
                mine.complete(result)
                return result
            } catch (cancelled: CancellationException) {
                // 发起方取消不应传染给共享同一请求的等待方
                mine.complete(JmxResult.Failure(JmxError.Network("共享请求被发起方取消")))
                throw cancelled
            } catch (t: Throwable) {
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlightRequests.remove(key, mine)
            }
        }
    }

    private suspend fun requestJsonDirect(request: ApiRequest): JmxResult<JsonElement> {
        return when (val response = requestJsonResponse(request)) {
            is JmxResult.Success -> JmxResult.Success(response.value.data)
            is JmxResult.Failure -> response
        }
    }

    suspend fun requestJsonResponse(request: ApiRequest): JmxResult<JsonNetworkResponse> {
        val raw = when (val result = httpClient.execute(request)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val exchange = raw.toExchange(request)
        // 解密是 AES + Base64，紧接着还要 Gson 解析整份响应；首页/收藏一页就有几十上百 KB。
        // 这段必须离开调用方线程（Compose 的 LaunchedEffect 跑在主线程上）。
        val envelope = when (
            val decoded = withContext(Dispatchers.IO) {
                if (request.route.encryptedJson) {
                    responseDecoder.decodeEncryptedEnvelope(raw.body, raw.tokenTimestampSeconds)
                } else {
                    responseDecoder.decodePlainEnvelope(raw.body)
                }
            }
        ) {
            is JmxResult.Success -> decoded.value
            is JmxResult.Failure -> return JmxResult.Failure(decoded.error.withExchange(exchange))
        }
        if (request.requireSuccessCode && envelope.code != 200) {
            return JmxResult.Failure(
                JmxError.Api(
                    code = envelope.code,
                    message = envelope.errorMessage ?: "接口返回错误：${envelope.code}",
                    exchange = exchange
                )
            )
        }
        return envelope.data?.let { JmxResult.Success(JsonNetworkResponse(data = it, exchange = exchange)) }
            ?: JmxResult.Failure(JmxError.Schema("API 响应 data 为空", field = "data", exchange = exchange))
    }

    suspend fun requestText(request: ApiRequest): JmxResult<String> {
        return when (val raw = requestTextResponse(request)) {
            is JmxResult.Success -> JmxResult.Success(raw.value.text)
            is JmxResult.Failure -> raw
        }
    }

    suspend fun requestTextResponse(request: ApiRequest): JmxResult<TextNetworkResponse> {
        return when (val raw = httpClient.execute(request)) {
            is JmxResult.Success -> JmxResult.Success(
                TextNetworkResponse(
                    text = raw.value.body,
                    exchange = raw.value.toExchange(request)
                )
            )
            is JmxResult.Failure -> raw
        }
    }

    private fun RawNetworkResponse.toExchange(request: ApiRequest): NetworkExchange {
        return NetworkExchange(
            route = request.route.path,
            requestUrl = requestUrl,
            statusCode = statusCode,
            contentType = contentType,
            tokenTimestampSeconds = tokenTimestampSeconds,
            bodySample = bodySampler.sample(body)
        )
    }
}

/**
 * 缓存里的内容可能因进程被杀、磁盘写坏而截断。解析失败当作未命中，直接走网络，
 * 而不是把损坏内容当错误抛给调用方。
 */
private fun String.parseJsonOrNull(): JsonElement? =
    runCatching { JsonParser.parseString(this) }.getOrNull()?.takeUnless { it.isJsonNull }

fun JmxError.withExchange(exchange: NetworkExchange): JmxError {
    return when (this) {
        is JmxError.Api -> copy(exchange = exchange)
        is JmxError.Decode -> copy(exchange = exchange)
        is JmxError.EmptyData -> copy(exchange = exchange)
        is JmxError.Http -> copy(exchange = exchange)
        is JmxError.Schema -> copy(exchange = exchange)
        is JmxError.Domain,
        is JmxError.Network,
        is JmxError.Unknown -> this
    }
}
