package app.prismia.plus.core.image

import app.prismia.plus.core.cache.ProtocolStateStore
import app.prismia.plus.core.network.HostHealth
import app.prismia.plus.core.protocol.JmxProtocolConstants
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 单台图片 CDN 的健康记录。字段与打分口径刻意与
 * [app.prismia.plus.core.network.ApiEndpoint] 保持一致，便于两侧对照与复用同一套衰减规则。
 */
data class ImageHostHealth(
    /** 主机名，不含 scheme。 */
    val host: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailureCount: Int = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastLatencyMillis: Long? = null,
    val averageLatencyMillis: Long? = null,
    val unavailableUntilMillis: Long? = null,
    val lastFailureMessage: String? = null,
) {
    fun isAvailableAt(nowMillis: Long): Boolean {
        return unavailableUntilMillis == null || unavailableUntilMillis <= nowMillis
    }

    fun healthScore(nowMillis: Long): Int {
        if (!isAvailableAt(nowMillis)) return -1000
        val successBonus = successCount.coerceAtMost(10) * 3
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
        // 图片体积远大于接口响应，延迟差异对观感的影响也更直接，
        // 因此比接口侧更看重延迟：每 200ms 扣 1 分，上限 30。
        val latencyPenalty = ((averageLatencyMillis ?: 0L) / 200L).coerceAtMost(30L).toInt()
        return 100 + successBonus - failurePenalty - consecutivePenalty - latencyPenalty
    }
}

/**
 * 图片 CDN 的线路表与选路依据。
 *
 * 接口侧一直有 [app.prismia.plus.core.network.ApiEndpointManager] 按健康度选机器并在失败后换机，
 * 图片侧此前完全没有：封面与漫画页固定打在 `img_host`（或内置表第一台）上，
 * 那一台慢、被墙或过载时，除了整页白屏没有任何自愈——而图片请求占全部请求量的绝大多数。
 * 这个类补上同一套逻辑：成功加分、失败罚分并退避、延迟按 EWMA 累计、罚分随时间衰减，
 * 选路结果落盘，下次冷启动第一张封面就打在上次最优的那台上。
 *
 * 手动钉住的线路（设置页里选的）优先级最高：钉住后 [current] 只返回它，
 * 也不参与自动选路，与接口侧 manual endpoint 的语义一致。
 */
class ImageHostRegistry(
    initialHosts: List<String> = JmxProtocolConstants.DefaultImageHosts.shuffled(),
    private val protocolStateStore: ProtocolStateStore? = null,
    private val maxFailuresBeforeDemote: Int = 2,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    private var hosts: List<ImageHostHealth> = buildInitialHosts(initialHosts)
    private var manualHostName: String? = protocolStateStore?.manualImageHost()?.hostNameOrNull()

    private fun buildInitialHosts(initialHosts: List<String>): List<ImageHostHealth> {
        val remote = protocolStateStore?.remoteImageHost()?.hostNameOrNull()
        val preferred = protocolStateStore?.preferredAutoImageHost()?.hostNameOrNull()
        // 远程下发的 img_host 排在内置表前面：它是服务端认为当前该用的那台。
        // 上次自动选出的最优线路再提到最前，保证冷启动第一发请求不做无谓试探。
        val ordered = (listOfNotNull(remote) + initialHosts.mapNotNull { it.hostNameOrNull() })
            .distinct()
            .sortedBy { if (it == preferred) 0 else 1 }
        return ordered.map { ImageHostHealth(host = it) }
    }

    /** 当前该用的图片主机地址（带 scheme，末尾无斜杠）。 */
    fun current(): String = "https://${currentHost()}"

    /** 当前该用的图片主机名。 */
    fun currentHost(): String {
        synchronized(lock) {
            manualHostName?.let { return it }
            val now = nowMillis()
            val available = hosts.filter { it.isAvailableAt(now) }
            return (available.ifEmpty { hosts }).maxByOrNull { it.healthScore(now) }?.host
                ?: FALLBACK_HOST
        }
    }

    /**
     * 失败换机时的候补顺序：按健康度从高到低，排除已试过的。
     *
     * 手动钉住线路时返回空表——用户明确指定了机器，就不该悄悄换到别的机器上，
     * 那会让"手动选线路"这个设置失去意义（也无法据此判断所选线路到底好不好用）。
     */
    fun candidates(triedHosts: Set<String> = emptySet()): List<String> {
        synchronized(lock) {
            manualHostName?.let { manual ->
                return if (manual in triedHosts) emptyList() else listOf(manual)
            }
            val now = nowMillis()
            return hosts
                .filterNot { it.host in triedHosts }
                .sortedWith(compareByDescending<ImageHostHealth> { it.isAvailableAt(now) }
                    .thenByDescending { it.healthScore(now) })
                .map { it.host }
        }
    }

    /** [host] 是否在线路表里。拦截器据此判断"这个地址该不该由我接管改写"。 */
    fun knows(host: String): Boolean = synchronized(lock) { hosts.any { it.host == host } }

    fun all(): List<ImageHostHealth> = synchronized(lock) { hosts }

    fun manualHost(): String? = synchronized(lock) { manualHostName }?.let { "https://$it" }

    /** @param host null 或空白表示回到自动选路。 */
    fun useManualHost(host: String?) {
        val normalized = host?.hostNameOrNull()
        synchronized(lock) {
            manualHostName = normalized
            if (normalized != null) hosts = hosts.ensureHost(normalized)
        }
        protocolStateStore?.updateManualImageHost(normalized)
    }

    /**
     * 把 [host] 并入线路表，不落盘。
     *
     * /chapter 会给出 `data_original_domain`，阅读器就用它取图，而那台机器未必在内置表里。
     * [ImageHostRoutingInterceptor] 只接管表里认识的主机（不认识的地址不该被我们改写），
     * 所以不并进来的话，请求量最大的阅读路径恰恰享受不到选路与换机。
     */
    fun rememberHost(host: String?) {
        val normalized = host?.hostNameOrNull() ?: return
        synchronized(lock) { hosts = hosts.ensureHost(normalized) }
    }

    /** 记下 /setting 下发的 img_host，并把它并入线路表。 */
    fun rememberRemoteHost(host: String?) {
        val normalized = host?.hostNameOrNull() ?: return
        rememberHost(normalized)
        protocolStateStore?.updateRemoteImageHost(normalized)
    }

    fun markSuccess(host: String, latencyMillis: Long? = null) {
        val normalized = host.hostNameOrNull() ?: return
        val now = nowMillis()
        synchronized(lock) {
            hosts = hosts.ensureHost(normalized).map { entry ->
                if (entry.host != normalized) return@map entry
                val latency = latencyMillis?.coerceAtLeast(0L)
                entry.copy(
                    successCount = entry.successCount + 1,
                    failureCount = 0,
                    consecutiveFailureCount = 0,
                    lastSuccessAtMillis = now,
                    lastLatencyMillis = latency ?: entry.lastLatencyMillis,
                    averageLatencyMillis = entry.nextAverageLatency(latency),
                    unavailableUntilMillis = null,
                    lastFailureMessage = null,
                )
            }
        }
        persistPreferredHost()
    }

    fun markFailure(host: String, message: String?) {
        val normalized = host.hostNameOrNull() ?: return
        val now = nowMillis()
        synchronized(lock) {
            hosts = hosts.ensureHost(normalized).map { entry ->
                if (entry.host != normalized) return@map entry
                val consecutive = entry.consecutiveFailureCount + 1
                entry.copy(
                    failureCount = entry.failureCount + 1,
                    consecutiveFailureCount = consecutive,
                    lastFailureAtMillis = now,
                    unavailableUntilMillis = unavailableUntil(consecutive, now),
                    lastFailureMessage = message,
                )
            }
        }
        persistPreferredHost()
    }

    private fun ImageHostHealth.nextAverageLatency(latencyMillis: Long?): Long? {
        if (latencyMillis == null) return averageLatencyMillis
        val current = averageLatencyMillis ?: return latencyMillis
        return (current * 7 + latencyMillis) / 8
    }

    private fun persistPreferredHost() {
        val store = protocolStateStore ?: return
        val preferred = synchronized(lock) {
            if (manualHostName != null) return@synchronized null
            val now = nowMillis()
            val available = hosts.filter { it.isAvailableAt(now) }
            (available.ifEmpty { hosts }).maxByOrNull { it.healthScore(now) }?.host
        }
        if (preferred != null) store.updatePreferredAutoImageHost(preferred)
    }

    private fun List<ImageHostHealth>.ensureHost(host: String): List<ImageHostHealth> {
        return if (any { it.host == host }) this else this + ImageHostHealth(host = host)
    }

    private fun unavailableUntil(consecutiveFailures: Int, now: Long): Long? {
        if (consecutiveFailures < maxFailuresBeforeDemote.coerceAtLeast(1)) return null
        val exponent = (consecutiveFailures - maxFailuresBeforeDemote).coerceIn(0, 6)
        val delayMillis = 1_000L shl exponent
        return now + delayMillis.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private companion object {
        const val MAX_BACKOFF_MILLIS = 5 * 60 * 1000L
        const val FALLBACK_HOST = "cdn-msp.jmapiproxy1.cc"
    }
}

/** 线路表里的地址可带可不带 scheme，统一取出主机名。 */
private fun String.hostNameOrNull(): String? {
    val raw = trim()
    if (raw.isEmpty()) return null
    val withScheme = if ("://" in raw) raw else "https://$raw"
    return withScheme.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }
}
