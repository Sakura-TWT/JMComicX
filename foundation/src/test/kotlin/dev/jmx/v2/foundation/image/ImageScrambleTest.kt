package dev.jmx.v2.foundation.image

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
    fun extractsFilenameAndGifIgnoringQuery() {
        val url = "https://img.test/media/photos/123/00001.gif?cache=abc"

        assertEquals("00001", ImageScramble.imageFilename(url))
        assertTrue(ImageScramble.isGif(url))
        assertFalse(ImageScramble.isGif("https://img.test/00002.webp?x=1"))
    }
}
