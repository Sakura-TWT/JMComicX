package dev.jmx.v2.foundation.image

import dev.jmx.v2.foundation.crypto.JmxHash
import dev.jmx.v2.foundation.protocol.JmxProtocolConstants
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
        val httpUrl = url.toHttpUrlOrNull()
        val lastSegment = httpUrl?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return lastSegment.substringBeforeLast('.', lastSegment)
    }

    fun isGif(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull()
        val lastSegment = httpUrl?.pathSegments?.lastOrNull()
            ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return lastSegment.substringAfterLast('.', "").equals("gif", ignoreCase = true)
    }
}
