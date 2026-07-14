package dev.jmx.client.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlTest {
    @Test
    fun buildsIndexedAndNamedUrls() {
        assertEquals(
            "https://cdn.test/media/photos/12345/00003.webp",
            ImageUrl.of(
                imageHost = "cdn.test",
                photoId = "12345",
                index = 3,
                suffix = "webp"
            )
        )
        assertEquals(
            "https://cdn.test/media/photos/12345/00001.jpg?v=9",
            ImageUrl.ofFileName(
                imageHost = "https://cdn.test/",
                photoId = "12345",
                fileName = "00001.jpg",
                cacheQuery = "v=9"
            )
        )
    }
}
