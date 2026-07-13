package dev.jmx.client.core.network

import dev.jmx.client.core.cache.ProtocolStateStore
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
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
        val failurePenalty = failureCount.coerceAtMost(20) * 4
        val consecutivePenalty = consecutiveFailureCount.coerceAtMost(10) * 30
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

class ApiEndpointManager(
    initialHosts: List<String> = JmxProtocolConstants.DefaultApiHosts,
    private val maxFailuresBeforeDemote: Int = 2,
    private val protocolStateStore: ProtocolStateStore? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var endpoints: List<ApiEndpoint> = (protocolStateStore?.apiHosts() ?: initialHosts)
        .mapNotNull { it.normalizedBaseUrlOrNull() }
        .distinctBy { it.endpointKey() }
        .map { ApiEndpoint(it) }
    private var manualEndpoint: HttpUrl? = protocolStateStore?.manualApiHost()?.normalizedBaseUrlOrNull()

    fun current(): JmxResult<HttpUrl> {
        synchronized(lock) {
            manualEndpoint?.let { return JmxResult.Success(it) }
        }
        val now = nowMillis()
        val endpoint = synchronized(lock) {
            val available = endpoints.filter { it.isAvailableAt(now) }
            (available.ifEmpty { endpoints }).maxByOrNull { it.healthScore(now) }
        }
        return endpoint?.let { JmxResult.Success(it.url) }
            ?: JmxResult.Failure(JmxError.Domain("没有可用 API 域名"))
    }

    fun all(): List<ApiEndpoint> = synchronized(lock) { endpoints }

    fun selection(): ApiEndpointSelection {
        return synchronized(lock) {
            manualEndpoint?.let { ApiEndpointSelection.Manual(it) } ?: ApiEndpointSelection.Auto
        }
    }

    fun useAutoSelection() {
        synchronized(lock) {
            manualEndpoint = null
        }
        protocolStateStore?.updateManualApiHost(null)
    }

    fun useManualEndpoint(host: String): JmxResult<HttpUrl> {
        val url = host.normalizedBaseUrlOrNull()
            ?: return JmxResult.Failure(JmxError.Domain("手动 API 域名无效", endpoint = host))
        synchronized(lock) {
            manualEndpoint = url
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
            endpoints = parsed.map { ApiEndpoint(it) }
        }
        protocolStateStore?.updateApiHosts(parsed.map { it.toString() })
        return JmxResult.Success(all())
    }

    fun markSuccess(url: HttpUrl, latencyMillis: Long? = null) {
        val now = nowMillis()
        synchronized(lock) {
            endpoints = endpoints.map {
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
    }

    private fun ApiEndpoint.nextAverageLatency(latencyMillis: Long?): Long? {
        if (latencyMillis == null) return averageLatencyMillis
        val current = averageLatencyMillis ?: return latencyMillis
        return (current * 7 + latencyMillis) / 8
    }

    fun markFailure(url: HttpUrl, message: String?) {
        val now = nowMillis()
        synchronized(lock) {
            endpoints = endpoints.map {
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
