package dev.jmx.client.core.image

import dev.jmx.client.core.crypto.JmxHash

data class ImagePlan(
    val sourceUrl: String,
    val albumId: Int,
    val scrambleId: Int,
    val filename: String,
    val isGif: Boolean,
    val segmentCount: Int,
    val cacheKey: String
) {
    val requiresRestore: Boolean = !isGif && segmentCount > 1
}

class ImagePipeline {
    fun plan(sourceUrl: String, albumId: Int, scrambleId: Int): ImagePlan {
        val filename = ImageScramble.imageFilename(sourceUrl)
        val isGif = ImageScramble.isGif(sourceUrl)
        val segmentCount = if (isGif) {
            0
        } else {
            ImageScramble.segmentCount(scrambleId, albumId, filename)
        }
        return ImagePlan(
            sourceUrl = sourceUrl,
            albumId = albumId,
            scrambleId = scrambleId,
            filename = filename,
            isGif = isGif,
            segmentCount = segmentCount,
            cacheKey = JmxHash.md5Hex(sourceUrl)
        )
    }
}
