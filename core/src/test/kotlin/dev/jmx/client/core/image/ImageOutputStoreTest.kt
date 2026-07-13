package dev.jmx.client.core.image

import dev.jmx.client.core.download.DownloadResult
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOutputStoreTest {
    @Test
    fun inMemoryStoreCopiesBytesAndReturnsRecords() {
        val store = InMemoryImageOutputStore()
        val result = restoreResult(
            url = "https://img.test/media/photos/123/00001.webp",
            bytes = byteArrayOf(1, 2, 3),
            restored = true
        )
        val key = ImageOutputKey(index = 0, filename = "00001", cacheKey = "cache-a")

        val stored = store.put(key, result)
        result.bytes[0] = 9

        assertTrue(stored is JmxResult.Success)
        val record = (stored as JmxResult.Success).value
        assertEquals(key, record.key)
        assertEquals(3, record.byteCount)
        assertTrue(record.restored)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.bytes(key))
        assertEquals(listOf(record), store.records())
    }

    @Test
    fun batchRunnerStoresOnlySuccessfulRestoreResults() {
        val store = InMemoryImageOutputStore()
        val runner = ImageStoreBatchRunner(store)
        val success = ImageRestoreBatchResult(
            item = ImageRestoreBatchItem(1, imageRequest("https://img.test/media/photos/123/00002.gif")),
            result = JmxResult.Success(
                restoreResult(
                    url = "https://img.test/media/photos/123/00002.gif",
                    bytes = byteArrayOf(7, 8),
                    restored = false
                )
            )
        )
        val failure = ImageRestoreBatchResult(
            item = ImageRestoreBatchItem(0, imageRequest("https://img.test/media/photos/123/00001.webp")),
            result = JmxResult.Failure(JmxError.Network("failed"))
        )

        val stored = runner.save(listOf(failure, success))

        assertEquals(1, stored.size)
        assertEquals(1, stored.single().item.index)
        assertTrue(stored.single().result is JmxResult.Success)
        assertEquals("00002", stored.single().item.outputKey.filename)
        assertEquals("00002.gif", stored.single().item.outputKey.displayFilename)
        assertEquals("gif", stored.single().item.outputKey.extension)
        assertArrayEquals(byteArrayOf(7, 8), store.bytes(stored.single().item.outputKey))
    }

    private fun imageRequest(url: String): ImageDownloadRequest {
        return ImageDownloadRequest(
            sourceUrl = url,
            albumId = 300000,
            scrambleId = 1
        )
    }

    private fun restoreResult(
        url: String,
        bytes: ByteArray,
        restored: Boolean
    ): ImageRestoreResult {
        val plan = ImagePipeline().plan(url, albumId = 300000, scrambleId = 1)
        return ImageRestoreResult(
            plan = plan,
            download = DownloadResult(
                url = url,
                statusCode = 200,
                contentType = "image/webp",
                contentLength = bytes.size.toLong(),
                bytesWritten = bytes.size.toLong()
            ),
            bytes = bytes,
            contentType = "image/webp",
            restored = restored
        )
    }
}
