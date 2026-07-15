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

    @Test
    fun buildsAlbumCoverUrls() {
        assertEquals(
            "https://cdn.test/media/albums/1452925_3x4.jpg",
            ImageUrl.albumCover(
                imageHost = "cdn.test",
                albumId = "1452925"
            )
        )
        assertEquals(
            "https://cdn.test/media/albums/1452925_3x4.jpg",
            ImageUrl.resolveAlbumCover(
                imageHost = "https://cdn.test/",
                albumId = "1452925",
                rawImage = ""
            )
        )
        assertEquals(
            "https://cdn.test/media/albums/cover.jpg",
            ImageUrl.resolveAlbumCover(
                imageHost = "https://cdn.test/",
                albumId = "1452925",
                rawImage = "cover.jpg"
            )
        )
    }
}
