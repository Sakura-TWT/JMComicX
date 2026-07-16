package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicReaderLogicTest {
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
