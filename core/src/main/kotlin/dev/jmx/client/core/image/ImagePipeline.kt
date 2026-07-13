package dev.jmx.client.core.image

import dev.jmx.client.core.crypto.JmxHash

data class ImagePlan(
    val sourceUrl: String,
    val albumId: Int,
    val scrambleId: Int,
    val filename: String,
    val extension: String?,
    val displayFilename: String,
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
        val extension = ImageScramble.imageExtension(sourceUrl)
        val displayFilename = ImageScramble.imageDisplayFilename(sourceUrl)
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
            extension = extension,
            displayFilename = displayFilename,
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

    fun restoreRows(
        source: ByteArray,
        imageHeight: Int,
        bytesPerRow: Int,
        segmentCount: Int
    ): ByteArray {
        require(imageHeight >= 0) { "imageHeight must be >= 0" }
        require(bytesPerRow >= 0) { "bytesPerRow must be >= 0" }
        require(source.size == imageHeight * bytesPerRow) {
            "source size ${source.size} does not match imageHeight * bytesPerRow ${imageHeight * bytesPerRow}"
        }
        val moves = restoreMoves(imageHeight, segmentCount)
        if (moves.isEmpty()) return source.copyOf()
        val restored = ByteArray(source.size)
        for (move in moves) {
            val sourceOffset = move.sourceY * bytesPerRow
            val targetOffset = move.targetY * bytesPerRow
            val length = move.height * bytesPerRow
            source.copyInto(
                destination = restored,
                destinationOffset = targetOffset,
                startIndex = sourceOffset,
                endIndex = sourceOffset + length
            )
        }
        return restored
    }
}
