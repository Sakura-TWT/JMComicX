package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxResult
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
                DownloadRequest(server.url("/image.webp").toString()),
                sink
            )
        }

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(5L, value.bytesWritten)
        assertEquals("image/webp", value.contentType)
        assertArrayEquals(bytes, sink.bytes())
    }
}
