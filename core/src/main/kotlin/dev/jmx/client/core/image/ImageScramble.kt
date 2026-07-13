package dev.jmx.client.core.image

import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.JmxProtocolConstants
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ImageScramble {
    fun segmentCount(scrambleId: Int, albumId: Int, filename: String): Int {
        return when {
            albumId < scrambleId -> 0
            albumId < JmxProtocolConstants.Scramble268850 -> 10
            else -> {
                val modulo = if (albumId < JmxProtocolConstants.Scramble421926) 10 else 8
                val charCode = JmxHash.md5Hex("$albumId$filename").last().code
                charCode % modulo * 2 + 2
            }
        }
    }

    fun segmentCountByUrl(scrambleId: Int, albumId: Int, url: String): Int {
        return segmentCount(scrambleId, albumId, imageFilename(url))
    }

    fun imageFilename(url: String): String {
        val lastSegment = imageLastPathSegment(url)
        return lastSegment.substringBeforeLast('.', lastSegment)
    }

    fun imageExtension(url: String): String? {
        val lastSegment = imageLastPathSegment(url)
        return lastSegment
            .substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() && it != lastSegment }
            ?.lowercase()
    }

    fun imageDisplayFilename(url: String): String {
        val lastSegment = imageLastPathSegment(url)
        return lastSegment.takeIf { it.isNotBlank() } ?: imageFilename(url)
    }

    fun isGif(url: String): Boolean {
        return imageExtension(url).equals("gif", ignoreCase = true)
    }

    private fun imageLastPathSegment(url: String): String {
        val httpUrl = url.toHttpUrlOrNull()
        return httpUrl?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#')
    }
}
