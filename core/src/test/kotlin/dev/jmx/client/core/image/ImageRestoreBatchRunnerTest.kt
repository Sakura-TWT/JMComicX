package dev.jmx.client.core.image

import dev.jmx.client.core.download.ByteSink
import dev.jmx.client.core.download.DownloadRequest
import dev.jmx.client.core.download.DownloadResult
import dev.jmx.client.core.download.Downloader
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRestoreBatchRunnerTest {
    @Test
    fun returnsResultsInInputOrder() {
        val runner = ImageRestoreBatchRunner(
            executor = ImageRestoreExecutor(
                downloader = FakeDownloader { request, sink ->
                    sink.write(request.url.encodeToByteArray())
                    JmxResult.Success(successDownload(request.url, request.url.length))
                },
                codec = PassthroughCodec()
            ),
            maxConcurrency = 2
        )

        val results = kotlinx.coroutines.runBlocking {
            runner.downloadAndRestore(
                listOf(
                    imageRequest("https://img.test/2.gif"),
                    imageRequest("https://img.test/1.gif")
                )
            )
        }

        assertEquals(listOf(0, 1), results.map { it.item.index })
        assertTrue(results.all { it.result is JmxResult.Success })
        assertEquals(
            "https://img.test/2.gif",
            (results[0].result as JmxResult.Success).value.bytes.decodeToString()
        )
        assertEquals(
            "https://img.test/1.gif",
            (results[1].result as JmxResult.Success).value.bytes.decodeToString()
        )
    }

    @Test
    fun keepsFailuresAsItemResults() {
        val runner = ImageRestoreBatchRunner(
            executor = ImageRestoreExecutor(
                downloader = FakeDownloader { request, sink ->
                    if (request.url.endsWith("fail.gif")) {
                        JmxResult.Failure(JmxError.Network("failed"))
                    } else {
                        sink.write(byteArrayOf(1))
                        JmxResult.Success(successDownload(request.url, 1))
                    }
                },
                codec = PassthroughCodec()
            )
        )

        val results = kotlinx.coroutines.runBlocking {
            runner.downloadAndRestore(
                listOf(
                    imageRequest("https://img.test/ok.gif"),
                    imageRequest("https://img.test/fail.gif")
                )
            )
        }

        assertTrue(results[0].result is JmxResult.Success)
        assertTrue(results[1].result is JmxResult.Failure)
    }

    @Test
    fun limitsConcurrentRestoreDownloads() {
        val counter = ConcurrentCounter()
        val runner = ImageRestoreBatchRunner(
            executor = ImageRestoreExecutor(
                downloader = FakeDownloader { request, sink ->
                    counter.enter()
                    try {
                        delay(20)
                        sink.write(byteArrayOf(1))
                        JmxResult.Success(successDownload(request.url, 1))
                    } finally {
                        counter.leave()
                    }
                },
                codec = PassthroughCodec()
            ),
            maxConcurrency = 2
        )

        kotlinx.coroutines.runBlocking {
            runner.downloadAndRestore(
                (0 until 6).map { imageRequest("https://img.test/$it.gif") }
            )
        }

        assertEquals(2, counter.maxSeen)
    }

    private fun imageRequest(url: String): ImageDownloadRequest {
        return ImageDownloadRequest(
            sourceUrl = url,
            albumId = 300000,
            scrambleId = 1
        )
    }

    private fun successDownload(url: String, bytes: Int): DownloadResult {
        return DownloadResult(
            url = url,
            statusCode = 200,
            contentType = "image/gif",
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

    private class ConcurrentCounter {
        private val mutex = Mutex()
        private var current = 0
        var maxSeen = 0
            private set

        suspend fun enter() {
            mutex.withLock {
                current++
                maxSeen = maxOf(maxSeen, current)
            }
        }

        suspend fun leave() {
            mutex.withLock {
                current--
            }
        }
    }
}
