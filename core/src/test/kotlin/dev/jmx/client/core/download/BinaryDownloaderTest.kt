package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.result.JmxError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BinaryDownloaderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun writesResponseBytesToSink() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val events = mutableListOf<DownloadEvent>()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setBody(okio.Buffer().write(bytes))
        )
        val sink = MemoryByteSink()

        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader(bufferSize = 2).download(
                DownloadRequest(
                    url = server.url("/image.webp").toString(),
                    acceptedContentTypes = setOf("image/*"),
                    maxBytes = 10,
                    observer = events::add
                ),
                sink
            )
        }

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(5L, value.bytesWritten)
        assertEquals("image/webp", value.contentType)
        assertArrayEquals(bytes, sink.bytes())
        assertTrue(events[0] is DownloadEvent.Started)
        assertEquals(listOf(2L, 4L, 5L), events.filterIsInstance<DownloadEvent.Progress>().map { it.bytesRead })
        assertTrue(events.last() is DownloadEvent.Completed)
    }

    @Test
    fun rejectsUnexpectedContentType() {
        val events = mutableListOf<DownloadEvent>()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html></html>")
        )

        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader().download(
                DownloadRequest(
                    url = server.url("/image.webp").toString(),
                    acceptedContentTypes = setOf("image/*"),
                    observer = events::add
                ),
                MemoryByteSink()
            )
        }

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertTrue(error.message.contains("url=${server.url("/image.webp")}"))
        assertTrue(error.message.contains("contentType=text/html"))
        assertTrue(events.first() is DownloadEvent.Started)
        assertTrue(events.last() is DownloadEvent.Failed)
    }

    @Test
    fun rejectsContentLengthThatExceedsMaxBytes() {
        val bytes = ByteArray(6) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setHeader("Content-Length", bytes.size)
                .setBody(okio.Buffer().write(bytes))
        )

        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader(bufferSize = 4).download(
                DownloadRequest(
                    url = server.url("/image.webp").toString(),
                    acceptedContentTypes = setOf("image/webp"),
                    maxBytes = 5
                ),
                MemoryByteSink()
            )
        }

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertTrue(error.message.contains("contentLength=6"))
        assertTrue(error.message.contains("maxBytes=5"))
    }

    @Test
    fun rejectsStreamingBodyThatExceedsMaxBytesWhenLengthIsUnknown() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setChunkedBody("123456", 2)
        )

        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader(bufferSize = 4).download(
                DownloadRequest(
                    url = server.url("/image.webp").toString(),
                    acceptedContentTypes = setOf("image/webp"),
                    maxBytes = 5
                ),
                MemoryByteSink()
            )
        }

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertTrue(error.message.contains("bytesRead=6"))
        assertTrue(error.message.contains("maxBytes=5"))
    }

    @Test
    fun httpFailureIncludesDownloadContext() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/plain")
                .setBody("down")
        )

        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader().download(
                DownloadRequest(
                    url = server.url("/image.webp").toString(),
                    acceptedContentTypes = setOf("image/*")
                ),
                MemoryByteSink()
            )
        }

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Http)
        assertTrue(error.message.contains("status=503"))
        assertTrue(error.message.contains("contentType=text/plain"))
        assertTrue(error.message.contains("url=${server.url("/image.webp")}"))
    }
}
