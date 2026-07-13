package dev.jmx.client.core.image

import dev.jmx.client.core.download.ByteSink
import dev.jmx.client.core.download.DownloadRequest
import dev.jmx.client.core.download.DownloadResult
import dev.jmx.client.core.download.Downloader
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRestoreExecutorTest {
    @Test
    fun downloadsAndRestoresScrambledRows() {
        val sourceRows = byteArrayOf(0, 1, 2, 3, 4, 5)
        val downloader = FakeDownloader(sourceRows, contentType = "image/webp")
        val codec = RowBytesCodec(width = 1, height = 6, contentType = "image/webp")
        val executor = ImageRestoreExecutor(downloader, codec)

        val result = kotlinx.coroutines.runBlocking {
            executor.downloadAndRestore(
                ImageDownloadRequest(
                    sourceUrl = "https://img.test/media/photos/123/00001.webp",
                    albumId = 300000,
                    scrambleId = 1
                )
            )
        }

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertTrue(value.restored)
        assertTrue(value.plan.requiresRestore)
        assertArrayEquals(
            ImagePipeline().restoreRows(sourceRows, imageHeight = 6, bytesPerRow = 1, segmentCount = value.plan.segmentCount),
            value.bytes
        )
        assertEquals("image/webp", value.contentType)
    }

    @Test
    fun leavesGifBytesUntouched() {
        val bytes = byteArrayOf(10, 11, 12)
        val downloader = FakeDownloader(bytes, contentType = "image/gif")
        val codec = CountingCodec()
        val executor = ImageRestoreExecutor(downloader, codec)

        val result = kotlinx.coroutines.runBlocking {
            executor.downloadAndRestore(
                ImageDownloadRequest(
                    sourceUrl = "https://img.test/media/photos/123/00002.gif?cache=1",
                    albumId = 300000,
                    scrambleId = 1
                )
            )
        }

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertFalse(value.restored)
        assertArrayEquals(bytes, value.bytes)
        assertEquals(0, codec.decodeCount)
    }

    private class FakeDownloader(
        private val bytes: ByteArray,
        private val contentType: String?
    ) : Downloader {
        override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> {
            sink.write(bytes)
            return JmxResult.Success(
                DownloadResult(
                    url = request.url,
                    statusCode = 200,
                    contentType = contentType,
                    contentLength = bytes.size.toLong(),
                    bytesWritten = bytes.size.toLong()
                )
            )
        }
    }

    private class RowBytesCodec(
        private val width: Int,
        private val height: Int,
        private val contentType: String?
    ) : ImageRowCodec {
        override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
            return JmxResult.Success(
                DecodedImageRows(
                    width = width,
                    height = height,
                    bytesPerRow = width,
                    rows = bytes
                )
            )
        }

        override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
            return JmxResult.Success(RestoredImageBytes(image.rows, contentType))
        }
    }

    private class CountingCodec : ImageRowCodec {
        var decodeCount = 0

        override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
            decodeCount++
            return JmxResult.Success(DecodedImageRows(width = 1, height = bytes.size, bytesPerRow = 1, rows = bytes))
        }

        override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
            return JmxResult.Success(RestoredImageBytes(image.rows, sourceContentType))
        }
    }
}
