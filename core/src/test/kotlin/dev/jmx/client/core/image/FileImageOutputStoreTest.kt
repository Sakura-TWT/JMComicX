package dev.jmx.client.core.image

import dev.jmx.client.core.download.DownloadResult
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileImageOutputStoreTest {
    @Test
    fun writesImageBytesToStableFileName() {
        val directory = Files.createTempDirectory("jmx-images")
        val store = FileImageOutputStore(directory)
        val key = ImageOutputKey(index = 2, filename = "00002", cacheKey = "abcdef1234567890")
        val result = restoreResult(
            url = "https://img.test/media/photos/123/00002.webp?cache=1",
            bytes = byteArrayOf(1, 2, 3),
            contentType = "image/webp",
            restored = true
        )

        val stored = store.putFile(key, result)

        assertTrue(stored is JmxResult.Success)
        val value = (stored as JmxResult.Success).value
        assertEquals(key, value.record.key)
        assertEquals(3, value.record.byteCount)
        assertTrue(value.record.restored)
        assertEquals("00002_00002_abcdef123456.webp", value.path.fileName.toString())
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(value.path))
        assertEquals(value.path, store.path(key))
    }

    @Test
    fun replacesExistingFileAtomicallyFromCallerPerspective() {
        val directory = Files.createTempDirectory("jmx-images")
        val store = FileImageOutputStore(directory)
        val key = ImageOutputKey(index = 0, filename = "page/evil", cacheKey = "cache-key")

        store.putFile(key, restoreResult("https://img.test/old.jpg", byteArrayOf(1), "image/jpeg", restored = false))
        val stored = store.putFile(
            key,
            restoreResult("https://img.test/new.jpg", byteArrayOf(9, 8), "image/jpeg", restored = false)
        )

        assertTrue(stored is JmxResult.Success)
        val path = (stored as JmxResult.Success).value.path
        assertEquals("00000_page_evil_cache-key.jpg", path.fileName.toString())
        assertArrayEquals(byteArrayOf(9, 8), Files.readAllBytes(path))
    }

    @Test
    fun unknownContentTypeUsesImageFallbackExtension() {
        val directory = Files.createTempDirectory("jmx-images")
        val store = FileImageOutputStore(directory)
        val key = ImageOutputKey(index = 1, filename = "", cacheKey = "cache")

        val stored = store.putFile(
            key,
            restoreResult("https://img.test/raw", byteArrayOf(5), contentType = null, restored = false)
        )

        assertTrue(stored is JmxResult.Success)
        val path = (stored as JmxResult.Success).value.path
        assertEquals("00001_image_cache.img", path.fileName.toString())
    }

    @Test
    fun samePageWithDifferentQueryWritesDistinctFiles() {
        val directory = Files.createTempDirectory("jmx-images")
        val store = FileImageOutputStore(directory)
        val first = restoreResult(
            "https://img.test/media/photos/123/00001.webp?cache=a",
            byteArrayOf(1),
            contentType = null,
            restored = true
        )
        val second = restoreResult(
            "https://img.test/media/photos/123/00001.webp?cache=b",
            byteArrayOf(2),
            contentType = null,
            restored = true
        )
        val firstKey = ImageStoreBatchItem(index = 0, result = first).outputKey
        val secondKey = ImageStoreBatchItem(index = 0, result = second).outputKey

        val firstStored = store.putFile(firstKey, first)
        val secondStored = store.putFile(secondKey, second)

        assertTrue(firstStored is JmxResult.Success)
        assertTrue(secondStored is JmxResult.Success)
        val firstPath = (firstStored as JmxResult.Success).value.path
        val secondPath = (secondStored as JmxResult.Success).value.path
        assertTrue(firstPath != secondPath)
        assertEquals("webp", firstKey.extension)
        assertEquals("00001.webp", firstKey.displayFilename)
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(firstPath))
        assertArrayEquals(byteArrayOf(2), Files.readAllBytes(secondPath))
    }

    private fun restoreResult(
        url: String,
        bytes: ByteArray,
        contentType: String?,
        restored: Boolean
    ): ImageRestoreResult {
        val plan = ImagePipeline().plan(url, albumId = 300000, scrambleId = 1)
        return ImageRestoreResult(
            plan = plan,
            download = DownloadResult(
                url = url,
                statusCode = 200,
                contentType = contentType,
                contentLength = bytes.size.toLong(),
                bytesWritten = bytes.size.toLong()
            ),
            bytes = bytes,
            contentType = contentType,
            restored = restored
        )
    }
}
