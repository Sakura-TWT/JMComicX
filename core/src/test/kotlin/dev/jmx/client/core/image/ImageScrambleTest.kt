package dev.jmx.client.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScrambleTest {
    @Test
    fun returnsZeroWhenAlbumBeforeScrambleId() {
        assertEquals(0, ImageScramble.segmentCount(scrambleId = 220980, albumId = 100, filename = "00001"))
    }

    @Test
    fun returnsTenForOldAlbumsAfterScramble() {
        assertEquals(10, ImageScramble.segmentCount(scrambleId = 1, albumId = 220981, filename = "00001"))
    }

    @Test
    fun matchesPythonSegmentFormulaAcrossAlgorithmEras() {

        assertEquals(16, ImageScramble.segmentCount(220980, 300000, "00001"))
        assertEquals(12, ImageScramble.segmentCount(220980, 500000, "00001"))
        assertEquals(6, ImageScramble.segmentCount(220980, 438516, "00001"))
        assertEquals(
            6,
            ImageScramble.segmentCountByUrl(
                scrambleId = 220980,
                albumId = 438516,
                url = "https://cdn.test/media/photos/438516/00001.webp?v=1"
            )
        )
    }

    @Test
    fun extractsFilenameAndGifIgnoringQuery() {
        val url = "https://img.test/media/photos/123/00001.gif?cache=abc"

        assertEquals("00001", ImageScramble.imageFilename(url))
        assertEquals("gif", ImageScramble.imageExtension(url))
        assertEquals("00001.gif", ImageScramble.imageDisplayFilename(url))
        assertTrue(ImageScramble.isGif(url))
        assertFalse(ImageScramble.isGif("https://img.test/00002.webp?x=1"))
    }
}
