package dev.jmx.client.core.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.jmx.client.core.cache.JsonResponseCache
import dev.jmx.client.core.result.NetworkExchange
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import dev.jmx.client.core.protocol.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val sharedScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val workGeneration: () -> Long = { 0L },
    private val metrics: RequestMetricsRecorder? = null,
) {
    private val sharedReads = SharedReadRequests<JmxResult<JsonElement>>(sharedScope)
    private val revalidations = ConcurrentHashMap<String, Job>()
    private class CacheEpoch {
        val users = AtomicInteger()
        val mutex = Mutex()
        var latest = 0L
        var written: JsonElement? = null
    }
    private val cacheEpochs = ConcurrentHashMap<String, CacheEpoch>()
    private val refreshSequence = AtomicLong()
    private val cacheHits = AtomicInteger(0)
    private val staleServedRequests = AtomicInteger(0)

    /** 因并发去重而免于重复发出的请求数，用于评估去重收益 */
    val deduplicatedRequestCount: Int get() = sharedReads.joinedCount.get()
    val activeSharedReadCount: Int get() = sharedReads.activeCount

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
            nowMillis = nowMillis,
            sharedScope = sharedScope,
            workGeneration = workGeneration,
            metrics = metrics,
        )
    }

    suspend fun requestJson(request: ApiRequest): JmxResult<JsonElement> = requestJsonSnapshot(httpClient.snapshot(request))

    private suspend fun requestJsonSnapshot(request: ApiRequest): JmxResult<JsonElement> {
        val namespace = cacheNamespace()
        val generation = cacheGeneration()
        val identity = listOf(namespace, generation, workGeneration().toString(), request.contentLanguageSnapshot.orEmpty())
            .joinToString("") { "${it.length}:$it" }
        val cache = responseCache
        val rule = cachePolicy?.ruleFor(request)
        if (cache == null || rule == null) return requestJsonDeduplicated(request, identity = identity)

        val key = "$namespace|${request.dedupKey()}"
        val cached = if (request.forceRefresh) null else {
            val started = System.nanoTime()
            readCache(cache, key, generation).also { metrics?.stage(request.route.path, LoadStage.CACHE_READ, System.nanoTime() - started) }
        }
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
                scheduleRevalidation(request, key, generation, identity)
                return JmxResult.Success(parsed)
            }
        }

        return fetchAndCache(request, key, generation, identity)
    }

    /** Only active keys occupy memory. A pre-refresh read cannot commit after a newer refresh. */
    private suspend fun fetchAndCache(request: ApiRequest, key: String, generation: String, identity: String): JmxResult<JsonElement> {
        val cache = responseCache ?: return requestJsonDeduplicated(request, identity = identity)
        val epoch = cacheEpochs.compute(key) { _, existing -> (existing ?: CacheEpoch()).also { it.users.incrementAndGet() } }!!
        try {
            val ticket = withContext(NonCancellable) {
                epoch.mutex.withLock {
                    if (request.forceRefresh) epoch.latest = refreshSequence.incrementAndGet()
                    epoch.latest
                }
            }
            val result = requestJsonDeduplicated(request, ticket, identity)
            if (result is JmxResult.Success) {
                val started = System.nanoTime()
                writeCache(cache, key, result.value, epoch, ticket, generation)
                metrics?.stage(request.route.path, LoadStage.CACHE_WRITE, System.nanoTime() - started)
            }
            return result
        } finally {
            cacheEpochs.compute(key) { _, current ->
                if (current !== epoch) current else if (epoch.users.decrementAndGet() == 0) null else epoch
            }
        }
    }

    /**
     * 读缓存并解析，一次线程切换里做完。
     *
     * 读要碰磁盘、解析要走一遍 Gson，调用方却是 Compose 的 LaunchedEffect（主线程）。
     * 返回 age 而不是 Entry，是为了让 [nowMillis] 与解析结果在同一次快照里取齐。
     */
    private suspend fun readCache(cache: JsonResponseCache, key: String, generation: String): Pair<Long, JsonElement>? {
        val entry = withContext(Dispatchers.IO) { cache.read(key) } ?: return null
        if (entry.generation != generation) return null
        return withContext(PROCESSING_DISPATCHER) {
            val parsed = entry.json.parseJsonOrNull() ?: return@withContext null
            entry.ageMillis(nowMillis()) to parsed
        }
    }

    /** 写缓存：`toString()` 要把整棵树重新序列化（首页一次几十上百 KB），加上落盘，同样不能留在主线程。 */
    private suspend fun writeCache(cache: JsonResponseCache, key: String, value: JsonElement, epoch: CacheEpoch, ticket: Long, generation: String) {
        withContext(Dispatchers.IO) {
            epoch.mutex.withLock {
                if (epoch.latest == ticket && epoch.written !== value && cacheGeneration() == generation) {
                    cache.write(key, value.toString(), generation)
                    epoch.written = value
                }
            }
        }
    }

    private fun scheduleRevalidation(request: ApiRequest, key: String, generation: String, identity: String) {
        val scope = revalidationScope ?: return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            backgroundNetwork { if (cacheGeneration() == generation) fetchAndCache(request, key, generation, identity) }
        }
        val existing = revalidations.putIfAbsent("$generation|$key", task)
        if (existing != null) { task.cancel(); return }
        task.invokeOnCompletion { revalidations.remove("$generation|$key", task) }
        task.start()
    }

    private suspend fun requestJsonDeduplicated(request: ApiRequest, cacheEpoch: Long = 0, identity: String): JmxResult<JsonElement> {
        // Separate write intents must never be merged (particularly favorite toggles).
        if (!deduplicateInFlightRequests || request.route.method != HttpMethod.Get) return requestJsonDirect(request)
        val key = "$identity|" + request.dedupKey() +
            (if (request.forceRefresh) "|refresh" else "") + (if (cacheEpoch != 0L) "|epoch=$cacheEpoch" else "")
        return sharedReads.await(key) { requestJsonDirect(request) }
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
        val decodeStarted = System.nanoTime()
        val envelope = when (
            val decoded = withContext(PROCESSING_DISPATCHER) {
                if (request.route.encryptedJson) {
                    responseDecoder.decodeEncryptedEnvelope(raw.body, raw.tokenTimestampSeconds)
                } else {
                    responseDecoder.decodePlainEnvelope(raw.body)
                }
            }.also { metrics?.stage(request.route.path, LoadStage.DECODE, System.nanoTime() - decodeStarted) }
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
    private companion object {
        val PROCESSING_DISPATCHER = Dispatchers.Default.limitedParallelism(4)
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
