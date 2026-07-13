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

    fun syncAvsCookieToHosts(apiHosts: List<String>): JmxResult<List<Cookie>> {
        val avs = cookieStore.snapshot()
            .firstOrNull { it.name.equals(AVS_COOKIE_NAME, ignoreCase = true) }
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

    fun cookies(): List<Cookie> = cookieStore.snapshot()

    private companion object {
        const val AVS_COOKIE_NAME = "AVS"
    }
}
