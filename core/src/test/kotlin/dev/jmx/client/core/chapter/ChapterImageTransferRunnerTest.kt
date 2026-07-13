package dev.jmx.client.core.chapter

import dev.jmx.client.core.download.ByteSink
import dev.jmx.client.core.download.DownloadRequest
import dev.jmx.client.core.download.DownloadResult
import dev.jmx.client.core.download.Downloader
import dev.jmx.client.core.image.DecodedImageRows
import dev.jmx.client.core.image.ImageOutputStore
import dev.jmx.client.core.image.ImageOutputKey
import dev.jmx.client.core.image.ImageOutputRecord
import dev.jmx.client.core.image.ImageRestoreBatchRunner
import dev.jmx.client.core.image.ImageRestoreExecutor
import dev.jmx.client.core.image.ImageRestoreResult
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.ImageStoreBatchRunner
import dev.jmx.client.core.image.InMemoryImageOutputStore
import dev.jmx.client.core.image.RestoredImageBytes
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterImageTransferRunnerTest {
    @Test
    fun transfersChapterImagesToOutputStore() {
        val store = InMemoryImageOutputStore()
        val runner = transferRunner(
            downloader = FakeDownloader { request, sink ->
                val bytes = request.url.substringAfterLast('/').encodeToByteArray()
                sink.write(bytes)
                JmxResult.Success(successDownload(request.url, bytes.size, "image/gif"))
            },
            store = store
        )

        val report = kotlinx.coroutines.runBlocking {
            runner.transfer(
                template = template(listOf("00001.gif", "00002.gif")),
                options = ChapterImageTransferOptions(headers = mapOf("referer" to "https://jm.test"))
            )
        }

        assertEquals(2, report.totalCount)
        assertEquals(2, report.restoredOrOriginalCount)
        assertEquals(2, report.storedCount)
        assertEquals(0, report.failedCount)
        assertEquals(listOf(0, 1), report.storeResults.map { it.item.index })
        val firstKey = report.storeResults.first().item.outputKey
        assertEquals("00001", firstKey.filename)
        assertEquals("00001.gif", firstKey.displayFilename)
        assertEquals("gif", firstKey.extension)
        assertArrayEquals("00001.gif".encodeToByteArray(), store.bytes(firstKey))
    }

    @Test
    fun reportsDownloadFailuresWithoutStoringThem() {
        val store = InMemoryImageOutputStore()
        val runner = transferRunner(
            downloader = FakeDownloader { request, sink ->
                if (request.url.endsWith("00002.gif")) {
                    JmxResult.Failure(JmxError.Network("failed"))
                } else {
                    sink.write(byteArrayOf(1))
                    JmxResult.Success(successDownload(request.url, 1, "image/gif"))
                }
            },
            store = store
        )

        val report = kotlinx.coroutines.runBlocking {
            runner.transfer(template(listOf("00001.gif", "00002.gif")))
        }

        assertEquals(2, report.totalCount)
        assertEquals(1, report.restoredOrOriginalCount)
        assertEquals(1, report.storedCount)
        assertEquals(1, report.failedCount)
        assertTrue(report.restoreResults[1].result is JmxResult.Failure)
        assertEquals(1, store.records().size)
    }

    @Test
    fun reportsStoreFailuresSeparatelyFromRestoreSuccess() {
        val runner = transferRunner(
            downloader = FakeDownloader { request, sink ->
                sink.write(byteArrayOf(1))
                JmxResult.Success(successDownload(request.url, 1, "image/gif"))
            },
            store = FailingStore()
        )

        val report = kotlinx.coroutines.runBlocking {
            runner.transfer(template(listOf("00001.gif")))
        }

        assertEquals(1, report.totalCount)
        assertEquals(1, report.restoredOrOriginalCount)
        assertEquals(0, report.storedCount)
        assertEquals(1, report.failedCount)
        assertTrue(report.storeResults.single().result is JmxResult.Failure)
    }

    private fun transferRunner(
        downloader: Downloader,
        store: ImageOutputStore
    ): ChapterImageTransferRunner {
        return ChapterImageTransferRunner(
            restoreBatchRunner = ImageRestoreBatchRunner(
                ImageRestoreExecutor(downloader, PassthroughCodec())
            ),
            storeBatchRunner = ImageStoreBatchRunner(store)
        )
    }

    private fun template(images: List<String>): ChapterTemplate {
        return ChapterTemplate(
            albumId = 300000,
            scrambleId = 1,
            speed = "0",
            imageHost = "https://img.test",
            chapterId = "123",
            cacheSuffix = "",
            imageFileNames = images
        )
    }

    private fun successDownload(url: String, bytes: Int, contentType: String): DownloadResult {
        return DownloadResult(
            url = url,
            statusCode = 200,
            contentType = contentType,
            contentLength = bytes.toLong(),
            bytesWritten = bytes.toLong()
        )
    }

    private class FakeDownloader(
        private val handler: suspend (DownloadRequest, ByteSink) -> JmxResult<DownloadResult>
    ) : Downloader {
        override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> {
            return handler(request, sink)
        }
    }

    private class PassthroughCodec : ImageRowCodec {
        override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
            return JmxResult.Success(DecodedImageRows(width = 1, height = bytes.size, bytesPerRow = 1, rows = bytes))
        }

        override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
            return JmxResult.Success(RestoredImageBytes(image.rows, sourceContentType))
        }
    }

    private class FailingStore : ImageOutputStore {
        override fun put(key: ImageOutputKey, result: ImageRestoreResult): JmxResult<ImageOutputRecord> {
            return JmxResult.Failure(JmxError.Schema("store failed"))
        }
    }
}
