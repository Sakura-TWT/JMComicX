package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadBatchRunnerTest {
    @Test
    fun returnsResultsInInputOrder() {
        val runner = DownloadBatchRunner(
            downloader = FakeDownloader { request, sink ->
                sink.write(request.url.encodeToByteArray())
                JmxResult.Success(
                    DownloadResult(
                        url = request.url,
                        statusCode = 200,
                        contentType = null,
                        contentLength = -1,
                        bytesWritten = request.url.length.toLong()
                    )
                )
            },
            maxConcurrency = 2
        )
        val sinks = mutableMapOf<Int, MemoryByteSink>()

        val results = kotlinx.coroutines.runBlocking {
            runner.download(
                listOf(
                    DownloadRequest("https://img.test/2.webp"),
                    DownloadRequest("https://img.test/1.webp")
                )
            ) { item ->
                MemoryByteSink().also { sinks[item.index] = it }
            }
        }

        assertEquals(listOf(0, 1), results.map { it.item.index })
        assertTrue(results.all { it.result is JmxResult.Success })
        assertEquals("https://img.test/2.webp", sinks.getValue(0).bytes().decodeToString())
        assertEquals("https://img.test/1.webp", sinks.getValue(1).bytes().decodeToString())
    }

    @Test
    fun keepsFailuresAsItemResults() {
        val runner = DownloadBatchRunner(
            downloader = FakeDownloader { request, _ ->
                if (request.url.endsWith("fail.webp")) {
                    JmxResult.Failure(JmxError.Network("failed"))
                } else {
                    JmxResult.Success(
                        DownloadResult(
                            url = request.url,
                            statusCode = 200,
                            contentType = null,
                            contentLength = 0,
                            bytesWritten = 0
                        )
                    )
                }
            }
        )

        val results = kotlinx.coroutines.runBlocking {
            runner.download(
                listOf(
                    DownloadRequest("https://img.test/ok.webp"),
                    DownloadRequest("https://img.test/fail.webp")
                )
            ) { MemoryByteSink() }
        }

        assertTrue(results[0].result is JmxResult.Success)
        assertTrue(results[1].result is JmxResult.Failure)
    }

    @Test
    fun limitsConcurrentDownloads() {
        val counter = ConcurrentCounter()
        val runner = DownloadBatchRunner(
            downloader = FakeDownloader { request, _ ->
                counter.enter()
                try {
                    delay(20)
                    JmxResult.Success(
                        DownloadResult(
                            url = request.url,
                            statusCode = 200,
                            contentType = null,
                            contentLength = 0,
                            bytesWritten = 0
                        )
                    )
                } finally {
                    counter.leave()
                }
            },
            maxConcurrency = 2
        )

        kotlinx.coroutines.runBlocking {
            runner.download(
                (0 until 6).map { DownloadRequest("https://img.test/$it.webp") }
            ) { MemoryByteSink() }
        }

        assertEquals(2, counter.maxSeen)
    }

    private class FakeDownloader(
        private val handler: suspend (DownloadRequest, ByteSink) -> JmxResult<DownloadResult>
    ) : Downloader {
        override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> {
            return handler(request, sink)
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
