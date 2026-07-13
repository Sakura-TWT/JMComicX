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

data class ImageSegmentMove(
    val sourceY: Int,
    val targetY: Int,
    val height: Int
)

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

    fun restoreMoves(imageHeight: Int, segmentCount: Int): List<ImageSegmentMove> {
        if (imageHeight <= 0 || segmentCount <= 1) return emptyList()
        val baseHeight = imageHeight / segmentCount
        val remainder = imageHeight % segmentCount
        return (0 until segmentCount).map { index ->
            var height = baseHeight
            var targetY = baseHeight * index
            val sourceY = imageHeight - baseHeight * (index + 1) - remainder
            if (index == 0) {
                height += remainder
            } else {
                targetY += remainder
            }
            ImageSegmentMove(
                sourceY = sourceY,
                targetY = targetY,
                height = height
            )
        }
    }
}
