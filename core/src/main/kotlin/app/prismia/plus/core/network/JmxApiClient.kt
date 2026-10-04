package app.prismia.plus.core.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import app.prismia.plus.core.cache.JsonResponseCache
import app.prismia.plus.core.result.NetworkExchange
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.protocol.HttpMethod
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     * Additional cache/request namespace. The effective language is also frozen
     * into the request itself before its identity is computed.
     */
    private val cacheNamespace: () -> String = { "" },
    private val cacheGeneration: () -> String = { "" },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val inFlightRequests = ConcurrentHashMap<FlightKey, CompletableDeferred<JmxResult<JsonElement>>>()
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

    private data class FlightKey(val request: String, val namespace: String, val generation: String)

    private data class RequestContext(val request: ApiRequest, val namespace: String, val generation: String) {
        val key = FlightKey(request.dedupKey(), namespace, generation)
        val cacheKey = "jm-json-v2:${namespace.length}:$namespace:${key.request}"
    }

    suspend fun requestJson(request: ApiRequest): JmxResult<JsonElement> {
        val context = RequestContext(httpClient.snapshotRequest(request), cacheNamespace(), cacheGeneration())
        val cache = responseCache
        val rule = if (context.request.route.method == HttpMethod.Get) cachePolicy?.ruleFor(context.request) else null
        if (cache == null || rule == null) return requestJsonDeduplicated(context)

        val cached = readCache(cache, context)
        if (cached != null) {
            val (age, parsed) = cached
            if (age < rule.freshMillis) {
                cacheHits.incrementAndGet()
                return JmxResult.Success(parsed)
            }
            if (age < rule.staleMillis) {
                cacheHits.incrementAndGet()
                staleServedRequests.incrementAndGet()
                revalidationScope?.launch { revalidate(context) }
                return JmxResult.Success(parsed)
            }
        }
        val result = requestJsonDeduplicated(context)
        if (result is JmxResult.Success) writeCache(cache, context, result.value)
        return result
    }

    private suspend fun readCache(cache: JsonResponseCache, context: RequestContext): Pair<Long, JsonElement>? =
        withContext(Dispatchers.IO) {
            val entry = cache.read(context.cacheKey) ?: return@withContext null
            if (entry.generation != context.generation) return@withContext null
            val parsed = entry.json.parseJsonOrNull() ?: return@withContext null
            entry.ageMillis(nowMillis()) to parsed
        }

    private suspend fun writeCache(cache: JsonResponseCache, context: RequestContext, value: JsonElement) {
        withContext(Dispatchers.IO) {
            // An older request must never be relabeled with a newer generation.
            if (context.generation != cacheGeneration() || context.namespace != cacheNamespace()) return@withContext
            cache.write(context.cacheKey, value.toString(), context.generation)
        }
    }

    private suspend fun revalidate(context: RequestContext) {
        val cache = responseCache ?: return
        when (val result = requestJsonDeduplicated(context)) {
            is JmxResult.Success -> writeCache(cache, context, result.value)
            is JmxResult.Failure -> Unit
        }
    }

    private suspend fun requestJsonDeduplicated(context: RequestContext): JmxResult<JsonElement> {
        // Repeated mutations represent distinct intents, even when their form bodies match.
        if (!deduplicateInFlightRequests || context.request.route.method != HttpMethod.Get) {
            return requestJsonDirect(context.request)
        }
        val key = context.key
        while (true) {
            currentCoroutineContext().ensureActive()
            val mine = CompletableDeferred<JmxResult<JsonElement>>()
            val existing = inFlightRequests.putIfAbsent(key, mine)
            if (existing != null) {
                deduplicatedRequests.incrementAndGet()
                try {
                    return existing.await().ownedCopy()
                } catch (_: SharedRequestAborted) {
                    // The leader cancelled its socket. A live reader can start or join a new GET.
                    continue
                }
            }
            try {
                val result = requestJsonDirect(context.request)
                mine.complete(result)
                return result.ownedCopy()
            } catch (cancelled: CancellationException) {
                inFlightRequests.remove(key, mine)
                mine.completeExceptionally(SharedRequestAborted())
                throw cancelled
            } catch (failure: Throwable) {
                mine.completeExceptionally(failure)
                throw failure
            } finally {
                inFlightRequests.remove(key, mine)
            }
        }
    }

    private class SharedRequestAborted : Exception()

    private suspend fun JmxResult<JsonElement>.ownedCopy(): JmxResult<JsonElement> = when (this) {
        is JmxResult.Success -> withContext(Dispatchers.Default) { JmxResult.Success(value.deepCopy()) }
        is JmxResult.Failure -> this
    }

    private suspend fun requestJsonDirect(request: ApiRequest): JmxResult<JsonElement> =
        when (val response = requestJsonResponsePrepared(request)) {
            is JmxResult.Success -> JmxResult.Success(response.value.data)
            is JmxResult.Failure -> response
        }

    suspend fun requestJsonResponse(request: ApiRequest): JmxResult<JsonNetworkResponse> =
        requestJsonResponsePrepared(httpClient.snapshotRequest(request))

    private suspend fun requestJsonResponsePrepared(request: ApiRequest): JmxResult<JsonNetworkResponse> {
        val raw = when (val result = httpClient.executePrepared(request)) {
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
