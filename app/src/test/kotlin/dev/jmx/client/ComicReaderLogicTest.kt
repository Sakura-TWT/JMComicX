package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicReaderLogicTest {
    @Test
    fun originalScaleIsNotTreatedAsZoomed() {
        assertTrue(!isReaderZoomed(1f))
        assertTrue(!isReaderZoomed(1.01f))
        assertTrue(isReaderZoomed(1.02f))
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

    @Test
    fun zoomOffsetKeepsFocusAndStaysInsideViewport() {
        val result = readerZoomOffsetAfterGesture(
            currentOffset = ReaderZoomOffset(0f, 0f),
            currentScale = 1f,
            requestedScale = 2f,
            focusX = 75f,
            focusY = 125f,
            viewportWidth = 300f,
            viewportHeight = 500f,
        )

        assertEquals(2f, result.scale, 0.001f)
        assertEquals(75f, result.offset.x, 0.001f)
        assertEquals(125f, result.offset.y, 0.001f)
    }

    @Test
    fun zoomOffsetClampsAndResetsAtMinimumScale() {
        val clamped = readerZoomOffsetAfterGesture(
            currentOffset = ReaderZoomOffset(0f, 0f),
            currentScale = 1f,
            requestedScale = 20f,
            focusX = 0f,
            focusY = 0f,
            viewportWidth = 300f,
            viewportHeight = 500f,
            panX = -10_000f,
            panY = 10_000f,
        )
        assertEquals(4f, clamped.scale, 0.001f)
        assertTrue(clamped.offset.x in -450f..450f)
        assertTrue(clamped.offset.y in -750f..750f)

        val reset = readerZoomOffsetAfterGesture(
            currentOffset = ReaderZoomOffset(100f, -100f),
            currentScale = 2f,
            requestedScale = 0.1f,
            focusX = 150f,
            focusY = 250f,
            viewportWidth = 300f,
            viewportHeight = 500f,
        )
        assertEquals(1f, reset.scale, 0.001f)
        assertEquals(0f, reset.offset.x, 0.001f)
        assertEquals(0f, reset.offset.y, 0.001f)
    }
}
