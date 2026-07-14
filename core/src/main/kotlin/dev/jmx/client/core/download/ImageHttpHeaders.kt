package dev.jmx.client.core.download

import dev.jmx.client.core.protocol.JmxProtocolConstants

object ImageHttpHeaders {
    const val REQUESTED_WITH = "com.JMComic3.app"

    fun default(
        refererHost: String? = null,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val referer = when {
            refererHost.isNullOrBlank() -> JmxProtocolConstants.DefaultApiHosts.first()
            refererHost.startsWith("http://") || refererHost.startsWith("https://") -> refererHost.trimEnd('/')
            else -> "https://${refererHost.trim().trimEnd('/')}"
        }
        return buildMap {

            put("user-agent", JmxProtocolConstants.MobileUserAgent)
            put(
                "Accept",
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
            put("X-Requested-With", REQUESTED_WITH)
            put("Referer", referer)
            put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            extra.forEach { (key, value) ->
                if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                    put(key, value)
                }
            }
        }
    }
}
