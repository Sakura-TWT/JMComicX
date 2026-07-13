package dev.jmx.v2.foundation.protocol

import dev.jmx.v2.foundation.crypto.JmxHash
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiTokenProviderTest {
    @Test
    fun createsAppTokenFromClockAndVersion() {
        val provider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = 1700566805L
            },
            versionProvider = { "2.0.26" }
        )

        val token = provider.create(ApiRoute.Album)

        assertEquals(1700566805L, token.timestampSeconds)
        assertEquals("1700566805,2.0.26", token.tokenParam)
        assertEquals(JmxHash.md5Hex("1700566805${JmxProtocolConstants.AppTokenSecret}"), token.token)
        assertEquals(SecretKind.App, token.secretKind)
    }

    @Test
    fun chapterTemplateUsesChapterSecret() {
        val provider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = 1700566805L
            }
        )

        val token = provider.create(ApiRoute.ChapterViewTemplate)

        assertEquals(JmxHash.md5Hex("1700566805${JmxProtocolConstants.ChapterTokenSecret}"), token.token)
        assertEquals(SecretKind.Chapter, token.secretKind)
    }
}
