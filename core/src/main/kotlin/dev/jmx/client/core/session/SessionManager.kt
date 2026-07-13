package dev.jmx.client.core.session

import dev.jmx.client.core.network.normalizedBaseUrlOrNull
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import okhttp3.Cookie

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

    fun cookies(): List<Cookie> = cookieStore.snapshot()
}
