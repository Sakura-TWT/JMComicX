package dev.jmx.client.core.chapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrambleIdCacheTest {
    @Test
    fun cachesByPhotoAndAlbum() {
        val cache = ScrambleIdCache()
        cache.put(photoId = "p1", scrambleId = 220980, albumId = "a1")
        assertEquals(220980, cache.get("p1"))
        assertEquals(220980, cache.get("p2", albumId = "a1"))
        assertNull(cache.get("p2"))
    }
}
