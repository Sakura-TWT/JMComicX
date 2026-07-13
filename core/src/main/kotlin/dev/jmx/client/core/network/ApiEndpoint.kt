package dev.jmx.client.core.network

import dev.jmx.client.core.cache.ProtocolStateStore
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ApiEndpoint(
    val url: HttpUrl,
    val failureCount: Int = 0,
    val lastFailureMessage: String? = null
)

class ApiEndpointManager(
    initialHosts: List<String> = JmxProtocolConstants.DefaultApiHosts,
    private val maxFailuresBeforeDemote: Int = 2,
    private val protocolStateStore: ProtocolStateStore? = null
) {
    private val lock = Any()
    private var endpoints: List<ApiEndpoint> = (protocolStateStore?.apiHosts() ?: initialHosts)
        .mapNotNull { it.normalizedBaseUrlOrNull() }
        .distinctBy { it.endpointKey() }
        .map { ApiEndpoint(it) }

    fun current(): JmxResult<HttpUrl> {
        val endpoint = synchronized(lock) {
            endpoints.firstOrNull { it.failureCount < maxFailuresBeforeDemote }
                ?: endpoints.minByOrNull { it.failureCount }
        }
        return endpoint?.let { JmxResult.Success(it.url) }
            ?: JmxResult.Failure(JmxError.Domain("没有可用 API 域名"))
    }

    fun all(): List<ApiEndpoint> = synchronized(lock) { endpoints }

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

    fun markSuccess(url: HttpUrl) {
        synchronized(lock) {
            endpoints = endpoints.map {
                if (it.url.endpointKey() == url.endpointKey()) it.copy(failureCount = 0, lastFailureMessage = null) else it
            }
        }
    }

    fun markFailure(url: HttpUrl, message: String?) {
        synchronized(lock) {
            endpoints = endpoints.map {
                if (it.url.endpointKey() == url.endpointKey()) {
                    it.copy(failureCount = it.failureCount + 1, lastFailureMessage = message)
                } else {
                    it
                }
            }
        }
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
