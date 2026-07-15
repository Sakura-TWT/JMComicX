package dev.jmx.client.core.image

import dev.jmx.client.core.protocol.JmxProtocolConstants

object ImageUrl {
    fun of(
        imageHost: String,
        photoId: String,
        index: Int,
        suffix: String = ".webp",
        cacheQuery: String? = null
    ): String {
        require(index >= 1) { "image index starts at 1, got $index" }
        val host = normalizeHost(imageHost)
        val fileName = "%05d%s".format(index, normalizeSuffix(suffix))
        val base = "$host/media/photos/$photoId/$fileName"
        return if (cacheQuery.isNullOrBlank()) {
            base
        } else if (cacheQuery.startsWith("?")) {
            base + cacheQuery
        } else {
            "$base?$cacheQuery"
        }
    }

    fun ofFileName(
        imageHost: String,
        photoId: String,
        fileName: String,
        cacheQuery: String? = null
    ): String {
        val host = normalizeHost(imageHost)
        val base = "$host/media/photos/$photoId/${fileName.trimStart('/')}"
        return when {
            cacheQuery.isNullOrBlank() -> base
            cacheQuery.startsWith("?") -> base + cacheQuery
            else -> "$base?$cacheQuery"
        }
    }

    fun albumCover(
        imageHost: String,
        albumId: String,
        variant: String = "3x4"
    ): String {
        val host = normalizeHost(imageHost)
        val id = albumId.trim().trimStart('/')
        require(id.isNotBlank()) { "album id is blank" }
        val normalizedVariant = variant.trim().trim('_').ifBlank { "3x4" }
        return "$host/media/albums/${id}_${normalizedVariant}.jpg"
    }

    fun resolveAlbumCover(
        imageHost: String,
        albumId: String,
        rawImage: String? = null
    ): String {
        val host = normalizeHost(imageHost)
        val raw = rawImage?.trim().orEmpty()
        if (raw.isBlank()) return albumCover(host, albumId)
        return when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$host$raw"
            raw.startsWith("media/", ignoreCase = true) -> "$host/$raw"
            raw.contains("/") -> "$host/${raw.trimStart('/')}"
            else -> "$host/media/albums/${raw.toAlbumCoverFileName()}"
        }
    }

    fun pickDefaultImageHost(index: Int = 0): String {
        val hosts = JmxProtocolConstants.DefaultImageHosts
        if (hosts.isEmpty()) return "https://cdn-msp.jmapiproxy1.cc"
        return hosts[index.mod(hosts.size)]
    }

    private fun normalizeHost(imageHost: String): String {
        val trimmed = imageHost.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    private fun normalizeSuffix(suffix: String): String {
        return if (suffix.startsWith(".")) suffix else ".$suffix"
    }

    private fun String.toAlbumCoverFileName(): String {
        if (contains(".")) return this
        if (endsWith("_3x4", ignoreCase = true)) return "$this.jpg"
        return "${this}_3x4.jpg"
    }
}
