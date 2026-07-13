package dev.jmx.client.core.image

import dev.jmx.client.core.crypto.JmxHash
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePipelineTest {
    @Test
    fun plansQueryUrlWithStableCacheKey() {
        val url = "https://img.test/media/photos/123/00001.webp?cache=abc"

        val plan = ImagePipeline().plan(url, albumId = 300000, scrambleId = 1)

        assertEquals("00001", plan.filename)
        assertEquals("webp", plan.extension)
        assertEquals("00001.webp", plan.displayFilename)
        assertFalse(plan.isGif)
        assertTrue(plan.segmentCount > 1)
        assertTrue(plan.requiresRestore)
        assertEquals(JmxHash.md5Hex(url), plan.cacheKey)
    }

    @Test
    fun gifDoesNotRequireRestoreEvenWithQuery() {
        val plan = ImagePipeline().plan(
            "https://img.test/media/photos/123/00002.gif?cache=abc",
            albumId = 300000,
            scrambleId = 1
        )

        assertEquals("00002", plan.filename)
        assertEquals("gif", plan.extension)
        assertEquals("00002.gif", plan.displayFilename)
        assertTrue(plan.isGif)
        assertEquals(0, plan.segmentCount)
        assertFalse(plan.requiresRestore)
    }

    @Test
    fun restoreMovesMatchReferenceScrambleGeometry() {
        val moves = ImagePipeline().restoreMoves(imageHeight = 11, segmentCount = 4)

        assertEquals(
            listOf(
                ImageSegmentMove(sourceY = 6, targetY = 0, height = 5),
                ImageSegmentMove(sourceY = 4, targetY = 5, height = 2),
                ImageSegmentMove(sourceY = 2, targetY = 7, height = 2),
                ImageSegmentMove(sourceY = 0, targetY = 9, height = 2)
            ),
            moves
        )
    }

    @Test
    fun restoreMovesAreEmptyWhenImageDoesNotNeedRestore() {
        assertEquals(emptyList<ImageSegmentMove>(), ImagePipeline().restoreMoves(imageHeight = 100, segmentCount = 1))
        assertEquals(emptyList<ImageSegmentMove>(), ImagePipeline().restoreMoves(imageHeight = 0, segmentCount = 4))
    }

    @Test
    fun restoreRowsAppliesReferenceMoves() {
        val sourceRows = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val restored = ImagePipeline().restoreRows(
            source = sourceRows,
            imageHeight = 11,
            bytesPerRow = 1,
            segmentCount = 4
        )

        assertArrayEquals(
            byteArrayOf(6, 7, 8, 9, 10, 4, 5, 2, 3, 0, 1),
            restored
        )
    }

    @Test
    fun restoreRowsPreservesMultiByteRows() {
        val sourceRows = byteArrayOf(
            0, 10,
            1, 11,
            2, 12,
            3, 13
        )

        val restored = ImagePipeline().restoreRows(
            source = sourceRows,
            imageHeight = 4,
            bytesPerRow = 2,
            segmentCount = 2
        )

        assertArrayEquals(
            byteArrayOf(
                2, 12,
                3, 13,
                0, 10,
                1, 11
            ),
            restored
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun restoreRowsRejectsMismatchedBufferSize() {
        ImagePipeline().restoreRows(
            source = byteArrayOf(1, 2, 3),
            imageHeight = 2,
            bytesPerRow = 2,
            segmentCount = 2
        )
    }
}
