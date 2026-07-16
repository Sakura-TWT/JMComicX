package dev.jmx.client

import dev.jmx.client.core.image.ImagePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicReaderLogicTest {
    @Test
    fun scaledRestoreSegmentsCoverTargetWithoutSeams() {
        val moves = ImagePipeline().restoreMoves(imageHeight = 1283, segmentCount = 10)
        val segments = scaleReaderTargetSegments(moves, sourceHeight = 1283, targetHeight = 1921)

        assertEquals(0, segments.first().top)
        assertEquals(1921, segments.last().bottom)
        assertTrue(segments.all { it.bottom > it.top })
        segments.zipWithNext().forEach { (current, next) ->
            assertEquals(current.bottom, next.top)
        }
    }

    @Test
    fun currentPageUsesLargestVisibleArea() {
        val page = selectCurrentReaderPage(
            visiblePages = listOf(
                ReaderVisiblePage(index = 4, offset = -700, size = 900),
                ReaderVisiblePage(index = 5, offset = 200, size = 900),
            ),
            viewportStart = 0,
            viewportEnd = 1000,
        )

        assertEquals(5, page)
    }

    @Test
    fun currentPageHandlesEmptyLayout() {
        assertEquals(
            0,
            selectCurrentReaderPage(emptyList(), viewportStart = 0, viewportEnd = 1000),
        )
    }

    @Test
    fun sliderPageRoundsAndClampsOnlyWhenReleased() {
        assertEquals(6, readerPageFromSlider(5.7f, totalPages = 20))
        assertEquals(0, readerPageFromSlider(-4f, totalPages = 20))
        assertEquals(19, readerPageFromSlider(24f, totalPages = 20))
        assertEquals(0, readerPageFromSlider(1f, totalPages = 1))
    }
}
