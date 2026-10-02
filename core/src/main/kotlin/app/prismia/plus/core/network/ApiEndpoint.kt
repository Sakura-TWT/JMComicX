package app.prismia.plus.core.network

import app.prismia.plus.core.cache.ProtocolStateStore
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ApiEndpoint(
    val url: HttpUrl,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailureCount: Int = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastLatencyMillis: Long? = null,
    val averageLatencyMillis: Long? = null,
    val unavailableUntilMillis: Long? = null,
    val lastFailureMessage: String? = null
) {
    fun isAvailableAt(nowMillis: Long): Boolean {
        return unavailableUntilMillis == null || unavailableUntilMillis <= nowMillis
    }

    fun healthScore(nowMillis: Long): Int {
        if (!isAvailableAt(nowMillis)) return -1000
        val successBonus = successCount.coerceAtMost(10) * 3
        // 罚分随失败时间衰减（见 HostHealth）：否则一台早就恢复的机器会被旧账永久压住，
        // 等到当前这台开始劣化时也换不回去。
        val failurePenalty = HostHealth.decayPenalty(
            penalty = failureCount.coerceAtMost(20) * 4,
            lastFailureAtMillis = lastFailureAtMillis,
            nowMillis = nowMillis,
        )
        val consecutivePenalty = HostHealth.decayPenalty(
            penalty = consecutiveFailureCount.coerceAtMost(10) * 30,
            lastFailureAtMillis = lastFailureAtMillis,
            nowMillis = nowMillis,
        )
        val latencyPenalty = ((averageLatencyMillis ?: 0L) / 250L).coerceAtMost(20L).toInt()
        return 100 + successBonus - failurePenalty - consecutivePenalty - latencyPenalty
    }
}

sealed interface ApiEndpointSelection {
    data object Auto : ApiEndpointSelection

    data class Manual(
        val url: HttpUrl
    ) : ApiEndpointSelection
}

/**
 * @param initialHosts 无任何本地记录时使用的初始域名表。默认值刻意每次构造都重新洗牌：
 *   内置表的顺序是写死的，若不打散，所有新安装的用户都会从同一台机器开始打第一个请求——
 *   那台一被墙就是全员卡在首屏。官方爬虫（jm_config.py 的 shuffled()）也是同一处理。
 *   洗牌只影响"零历史"这一种情况：一旦 [protocolStateStore] 里有域名表，就以它为准；
 *   显式传入 initialHosts 的调用方（测试、诊断工具）也保持给定顺序不变。
 */
class ApiEndpointManager(
    initialHosts: List<String> = JmxProtocolConstants.DefaultApiHosts.shuffled(),
    private val maxFailuresBeforeDemote: Int = 2,
    private val protocolStateStore: ProtocolStateStore? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var endpoints: List<ApiEndpoint> = (protocolStateStore?.apiHosts() ?: initialHosts)
        .mapNotNull { it.normalizedBaseUrlOrNull() }
        .distinctBy { it.endpointKey() }
        .let { urls ->
            val preferredKey = protocolStateStore?.preferredAutoApiHost()
                ?.normalizedBaseUrlOrNull()
                ?.endpointKey()
            urls.sortedBy { if (it.endpointKey() == preferredKey) 0 else 1 }
        }
        .map { ApiEndpoint(it) }
    private var autoEndpointKeys: Set<String> = endpoints.map { it.url.endpointKey() }.toSet()
    private var manualEndpoint: HttpUrl? = protocolStateStore?.manualApiHost()?.normalizedBaseUrlOrNull()
    private var sessionEndpointUrl: HttpUrl? = protocolStateStore?.sessionApiHost()?.normalizedBaseUrlOrNull()

    init {
        manualEndpoint?.let { endpoints = endpoints.ensureEndpoint(it) }
        sessionEndpointUrl?.let { endpoints = endpoints.ensureEndpoint(it) }
    }

    fun current(): JmxResult<HttpUrl> = current(excludedUrl = null)

    fun current(excludedUrl: HttpUrl?): JmxResult<HttpUrl> {
        synchronized(lock) {
            manualEndpoint?.let { return JmxResult.Success(it) }
        }
        val now = nowMillis()
        val excludedKey = excludedUrl?.endpointKey()
        val endpoint = synchronized(lock) {
            val pool = excludedKey?.let { key -> endpoints.filter { it.url.endpointKey() != key } }.orEmpty()
                .ifEmpty { endpoints }
            // 会话亲和优先于健康度：登录态绑在签发它的那台机器上（见 [useSessionEndpoint]），
            // 换机等于掉登录，而"稍慢一点"远好过"点收藏就要重新登录"。
            // 只在那台还没被降级时成立——真的连不上时还是要换机，
            // 换机后的 401 会触发静默重登，重登又会把亲和更新到新机器上。
            sessionAffinityEndpoint(pool, excludedKey, now)
                ?: run {
                    val available = pool.filter { it.isAvailableAt(now) }
                    (available.ifEmpty { pool }).maxByOrNull { it.healthScore(now) }
                }
        }
        return endpoint?.let { JmxResult.Success(it.url) }
            ?: JmxResult.Failure(JmxError.Domain("没有可用 API 域名"))
    }

    private fun sessionAffinityEndpoint(
        pool: List<ApiEndpoint>,
        excludedKey: String?,
        nowMillis: Long
    ): ApiEndpoint? {
        val key = sessionEndpointUrl?.endpointKey() ?: return null
        if (key == excludedKey) return null
        return pool.firstOrNull { it.url.endpointKey() == key }?.takeIf { it.isAvailableAt(nowMillis) }
    }

    fun all(): List<ApiEndpoint> = synchronized(lock) { endpoints }

    fun selection(): ApiEndpointSelection {
        return synchronized(lock) {
            manualEndpoint?.let { ApiEndpointSelection.Manual(it) } ?: ApiEndpointSelection.Auto
        }
    }

    /** 当前登录态绑定的域名；null 表示未登录或还不知道是哪一台。 */
    fun sessionEndpoint(): HttpUrl? = synchronized(lock) { sessionEndpointUrl }

    /**
     * 记住签发当前登录态的那台机器，登录成功后调用。
     *
     * 与 [useManualEndpoint] 的区别：这不是用户的选择，因此不写进"手动线路"设置、
     * 也不关掉自动选路——它只是把自动选路的第一顺位换成这台，且在这台被降级时自动让位。
     */
    fun useSessionEndpoint(host: String): JmxResult<HttpUrl> {
        val url = host.normalizedBaseUrlOrNull()
            ?: return JmxResult.Failure(JmxError.Domain("会话 API 域名无效", endpoint = host))
        synchronized(lock) {
            val previous = sessionEndpointUrl
            sessionEndpointUrl = url
            endpoints = endpoints.ensureEndpoint(url)
            previous?.takeIf { it.endpointKey() != url.endpointKey() }
                ?.let { endpoints = endpoints.removeUnreferencedEndpoint(it) }
        }
        protocolStateStore?.updateSessionApiHost(url.toString())
        return JmxResult.Success(url)
    }

    /** 退出登录时调用：没有会话，也就不该再为它牺牲选路自由。 */
    fun clearSessionEndpoint() {
        synchronized(lock) {
            val previous = sessionEndpointUrl
            sessionEndpointUrl = null
            previous?.let { endpoints = endpoints.removeUnreferencedEndpoint(it) }
        }
        protocolStateStore?.updateSessionApiHost(null)
        persistPreferredAutoEndpoint()
    }

    fun useAutoSelection() {
        synchronized(lock) {
            val previousManualEndpoint = manualEndpoint
            manualEndpoint = null
            previousManualEndpoint?.let {
                endpoints = endpoints.removeUnreferencedEndpoint(it)
            }
        }
        protocolStateStore?.updateManualApiHost(null)
        persistPreferredAutoEndpoint()
    }

    fun useManualEndpoint(host: String): JmxResult<HttpUrl> {
        val url = host.normalizedBaseUrlOrNull()
            ?: return JmxResult.Failure(JmxError.Domain("手动 API 域名无效", endpoint = host))
        synchronized(lock) {
            manualEndpoint = url
            endpoints = endpoints.ensureEndpoint(url)
        }
        protocolStateStore?.updateManualApiHost(url.toString())
        return JmxResult.Success(url)
    }

    fun replaceAll(hosts: List<String>): JmxResult<List<ApiEndpoint>> {
        val parsed = hosts
            .mapNotNull { it.normalizedBaseUrlOrNull() }
            .distinctBy { it.endpointKey() }
        if (parsed.isEmpty()) {
            return JmxResult.Failure(JmxError.Domain("远程 API 域名列表为空"))
        }
        synchronized(lock) {
            val existing = endpoints.associateBy { it.url.endpointKey() }
            autoEndpointKeys = parsed.map { it.endpointKey() }.toSet()
            endpoints = parsed.map { url -> existing[url.endpointKey()]?.copy(url = url) ?: ApiEndpoint(url) }
                .let { refreshed -> manualEndpoint?.let { refreshed.ensureEndpoint(it) } ?: refreshed }
                // 会话绑定的那台即使被远程列表剔除也得留着：踢掉它就等于让用户掉登录。
                .let { refreshed -> sessionEndpointUrl?.let { refreshed.ensureEndpoint(it) } ?: refreshed }
        }
        protocolStateStore?.updateApiHosts(parsed.map { it.toString() })
        persistPreferredAutoEndpoint()
        return JmxResult.Success(all())
    }

    fun markSuccess(url: HttpUrl, latencyMillis: Long? = null) {
        val now = nowMillis()
        synchronized(lock) {
            endpoints = endpoints.ensureEndpoint(url).map {
                if (it.url.endpointKey() == url.endpointKey()) {
                    val normalizedLatency = latencyMillis?.coerceAtLeast(0L)
                    it.copy(
                        successCount = it.successCount + 1,
                        failureCount = 0,
                        consecutiveFailureCount = 0,
                        lastSuccessAtMillis = now,
                        lastLatencyMillis = normalizedLatency ?: it.lastLatencyMillis,
                        averageLatencyMillis = it.nextAverageLatency(normalizedLatency),
                        unavailableUntilMillis = null,
                        lastFailureMessage = null
                    )
                } else {
                    it
                }
            }
        }
        persistPreferredAutoEndpoint()
    }

    private fun ApiEndpoint.nextAverageLatency(latencyMillis: Long?): Long? {
        if (latencyMillis == null) return averageLatencyMillis
        val current = averageLatencyMillis ?: return latencyMillis
        return (current * 7 + latencyMillis) / 8
    }

    fun markFailure(url: HttpUrl, message: String?) {
        val now = nowMillis()
        synchronized(lock) {
            endpoints = endpoints.ensureEndpoint(url).map {
                if (it.url.endpointKey() == url.endpointKey()) {
                    val consecutiveFailures = it.consecutiveFailureCount + 1
                    it.copy(
                        failureCount = it.failureCount + 1,
                        consecutiveFailureCount = consecutiveFailures,
                        lastFailureAtMillis = now,
                        unavailableUntilMillis = unavailableUntil(consecutiveFailures, now),
                        lastFailureMessage = message
                    )
                } else {
                    it
                }
            }
        }
        persistPreferredAutoEndpoint()
    }

    private fun persistPreferredAutoEndpoint() {
        val preferred = synchronized(lock) {
            if (manualEndpoint != null) return@synchronized null
            val now = nowMillis()
            val automatic = endpoints.filter { it.url.endpointKey() in autoEndpointKeys }
            val available = automatic.filter { it.isAvailableAt(now) }
            (available.ifEmpty { automatic }).maxByOrNull { it.healthScore(now) }?.url?.toString()
        }
        if (preferred != null) protocolStateStore?.updatePreferredAutoApiHost(preferred)
    }

    private fun List<ApiEndpoint>.ensureEndpoint(url: HttpUrl): List<ApiEndpoint> {
        return if (any { it.url.endpointKey() == url.endpointKey() }) {
            this
        } else {
            this + ApiEndpoint(url)
        }
    }

    /** 移除不再被任何一方引用的端点（既不在自动表里，也不是手动/会话钉住的那台）。 */
    private fun List<ApiEndpoint>.removeUnreferencedEndpoint(url: HttpUrl): List<ApiEndpoint> {
        val key = url.endpointKey()
        val stillReferenced = key in autoEndpointKeys ||
            key == manualEndpoint?.endpointKey() ||
            key == sessionEndpointUrl?.endpointKey()
        return if (stillReferenced) {
            this
        } else {
            filterNot { it.url.endpointKey() == key }
        }
    }

    private fun unavailableUntil(consecutiveFailures: Int, now: Long): Long? {
        if (consecutiveFailures < maxFailuresBeforeDemote.coerceAtLeast(1)) return null
        val exponent = (consecutiveFailures - maxFailuresBeforeDemote).coerceIn(0, 6)
        val delayMillis = 1_000L shl exponent
        return now + delayMillis.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private companion object {
        const val MAX_BACKOFF_MILLIS = 5 * 60 * 1000L
    }
}

private fun HttpUrl.endpointKey(): String = "$scheme://$host:$port"

fun String.normalizedBaseUrlOrNull(): HttpUrl? {
    val raw = trim()
    if (raw.isEmpty()) return null
    val withScheme = if ("://" in raw) raw else "https://$raw"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
}
