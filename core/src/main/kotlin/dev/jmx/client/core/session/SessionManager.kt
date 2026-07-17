package dev.jmx.client.core.session

import dev.jmx.client.core.network.normalizedBaseUrlOrNull
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SessionManager(
    private val cookieStore: CookieStore
) {
    fun clear() {
        cookieStore.clear()
    }

    fun installAvsCookie(apiHost: String, avs: String): JmxResult<Cookie> {
        if (avs.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("AVS 为空", field = "avs"))
        }
        val url = apiHost.normalizedBaseUrlOrNull()
            ?: return JmxResult.Failure(JmxError.Domain("API 域名无效", endpoint = apiHost))

        val cookie = Cookie.Builder()
            .name("AVS")
            .value(avs)
            .domain(url.host)
            .path("/")
            .httpOnly()
            .build()
        cookieStore.save(url, listOf(cookie))
        return JmxResult.Success(cookie)
    }

    fun syncAvsCookieToHosts(apiHosts: List<String>): JmxResult<List<Cookie>> {
        val avs = cookieStore.snapshot()
            .lastOrNull { it.name.equals(AVS_COOKIE_NAME, ignoreCase = true) }
            ?.value
            ?: return JmxResult.Failure(JmxError.Schema("AVS 不存在", field = AVS_COOKIE_NAME))
        val installed = mutableListOf<Cookie>()
        for (host in apiHosts) {
            when (val result = installAvsCookie(host, avs)) {
                is JmxResult.Success -> installed += result.value
                is JmxResult.Failure -> return result
            }
        }
        return JmxResult.Success(installed)
    }

    fun syncAvsCookieToHostsIfPresent(apiHosts: List<String>): JmxResult<List<Cookie>> {
        val avs = currentAvsValue() ?: return JmxResult.Success(emptyList())
        val installed = mutableListOf<Cookie>()
        for (host in apiHosts) {
            when (val result = installAvsCookie(host, avs)) {
                is JmxResult.Success -> installed += result.value
                is JmxResult.Failure -> return result
            }
        }
        return JmxResult.Success(installed)
    }

    fun commitCookies(requestUrl: String, cookies: List<Cookie>): JmxResult<List<Cookie>> {
        if (cookies.isEmpty()) return JmxResult.Success(emptyList())
        val url = requestUrl.toHttpUrlOrNull()
            ?: return JmxResult.Failure(JmxError.Domain("Cookie commit URL is invalid", endpoint = requestUrl))
        cookieStore.save(url, cookies)
        return JmxResult.Success(cookies)
    }

    fun replicateSessionCookiesToHosts(apiHosts: List<String>): JmxResult<Int> {
        val source = cookieStore.snapshot().latestByIdentity()
        if (source.isEmpty()) return JmxResult.Success(0)
        var installed = 0
        for (host in apiHosts.distinct()) {
            val url = host.normalizedBaseUrlOrNull()
                ?: return JmxResult.Failure(JmxError.Domain("API 域名无效", endpoint = host))
            val cloned = source.map { cookie -> cookie.forHost(url.host) }.latestByIdentity()
            cookieStore.save(url, cloned)
            installed += cloned.size
        }
        return JmxResult.Success(installed)
    }

    fun cookies(): List<Cookie> = cookieStore.snapshot()

    fun hasAvs(): Boolean = currentAvsValue() != null

    private fun currentAvsValue(): String? {
        return cookieStore.snapshot()
            .lastOrNull { it.name.equals(AVS_COOKIE_NAME, ignoreCase = true) }
            ?.value
    }

    private fun Cookie.forHost(host: String): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(if (path.isBlank()) "/" else path)

        if (name.equals(AVS_COOKIE_NAME, ignoreCase = true)) {
            builder.domain(host)
        } else {
            builder.hostOnlyDomain(host)
        }
        if (httpOnly) builder.httpOnly()
        if (secure) builder.secure()
        if (expiresAt < Long.MAX_VALUE / 2) {
            builder.expiresAt(expiresAt)
        }
        return builder.build()
    }

    private fun List<Cookie>.latestByIdentity(): List<Cookie> =
        asReversed()
            .distinctBy { "${it.domain}|${it.path}|${it.name}" }
            .asReversed()

    private companion object {
        const val AVS_COOKIE_NAME = "AVS"
    }
}
