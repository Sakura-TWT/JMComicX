package dev.jmx.client.core.image

import dev.jmx.client.core.crypto.JmxHash
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
        assertTrue(plan.isGif)
        assertEquals(0, plan.segmentCount)
        assertFalse(plan.requiresRestore)
    }
}
