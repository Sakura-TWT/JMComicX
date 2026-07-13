package dev.jmx.client.core.protocol

import dev.jmx.client.core.crypto.JmxHash

data class ApiToken(
    val timestampSeconds: Long,
    val version: String,
    val token: String,
    val tokenParam: String,
    val secretKind: SecretKind
)

enum class SecretKind {
    App,
    Chapter
}

interface ApiClock {
    fun nowSeconds(): Long
}

object SystemApiClock : ApiClock {
    override fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}

class ApiTokenProvider(
    private val clock: ApiClock = SystemApiClock,
    private val versionProvider: () -> String = { JmxProtocolConstants.DefaultApiVersion }
) {
    constructor(
        clock: ApiClock = SystemApiClock,
        apiVersionProvider: ApiVersionProvider
    ) : this(clock, { apiVersionProvider.current() })

    fun create(route: ApiRoute): ApiToken {
        val ts = clock.nowSeconds()
        val secret = route.tokenSecret
        val kind = if (secret == JmxProtocolConstants.ChapterTokenSecret) SecretKind.Chapter else SecretKind.App
        val version = versionProvider()
        return ApiToken(
            timestampSeconds = ts,
            version = version,
            token = JmxHash.md5Hex("$ts$secret"),
            tokenParam = "$ts,$version",
            secretKind = kind
        )
    }
}
