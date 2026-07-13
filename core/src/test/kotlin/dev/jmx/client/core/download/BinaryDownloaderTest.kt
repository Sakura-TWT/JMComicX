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
                    maxBytes = 10
                ),
                sink
            )
        }

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(5L, value.bytesWritten)
        assertEquals("image/webp", value.contentType)
        assertArrayEquals(bytes, sink.bytes())
    }

    @Test
    fun rejectsUnexpectedContentType() {
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
                    acceptedContentTypes = setOf("image/*")
                ),
                MemoryByteSink()
            )
        }

        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.Schema)
    }

    @Test
    fun rejectsBodyThatExceedsMaxBytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setBody("123456")
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
        assertTrue((result as JmxResult.Failure).error is JmxError.Schema)
    }
}
