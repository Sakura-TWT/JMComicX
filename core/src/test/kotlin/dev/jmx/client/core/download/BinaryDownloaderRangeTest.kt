package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class BinaryDownloaderRangeTest {
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
    fun resumesWithHttp206AndAppendsBytes() {
        val full = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val partial = full.copyOfRange(0, 3)
        val rest = full.copyOfRange(3, full.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 3-7/8")
                .setBody(Buffer().write(rest))
        )
        val path = Files.createTempFile("jmx-range-", ".bin")
        Files.write(path, partial)
        val sink = FileByteSink(path, append = true)
        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader().download(
                DownloadRequest(
                    url = server.url("/file.bin").toString(),
                    preferRangeResume = true,
                    rangeStartInclusive = partial.size.toLong()
                ),
                sink
            )
        }
        sink.close()
        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(206, value.statusCode)
        assertTrue(value.usedRange)
        assertEquals(3L, value.resumedFromOffset)
        assertEquals(5L, value.bytesWritten)
        assertArrayEquals(full, Files.readAllBytes(path))
        val recorded = server.takeRequest()
        assertEquals("bytes=3-", recorded.getHeader("Range"))
    }

    @Test
    fun fallsBackToFullDownloadWhenServerReturns200ForRange() {
        val full = byteArrayOf(9, 8, 7, 6, 5)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(full))
        )
        val path = Files.createTempFile("jmx-range-full-", ".bin")
        Files.write(path, byteArrayOf(1, 1, 1))
        val sink = FileByteSink(path, append = true)
        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader().download(
                DownloadRequest(
                    url = server.url("/full.bin").toString(),
                    preferRangeResume = true,
                    rangeStartInclusive = 3L
                ),
                sink
            )
        }
        sink.close()
        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(200, value.statusCode)
        assertFalse(value.usedRange)
        assertArrayEquals(full, Files.readAllBytes(path))
    }

    @Test
    fun fallsBackWhenServerReturns416() {
        val full = byteArrayOf(4, 5, 6)
        server.enqueue(MockResponse().setResponseCode(416).setBody("range not satisfiable"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(full))
        )
        val path = Files.createTempFile("jmx-range-416-", ".bin")
        Files.write(path, byteArrayOf(1))
        val sink = FileByteSink(path, append = true)
        val result = kotlinx.coroutines.runBlocking {
            BinaryDownloader().download(
                DownloadRequest(
                    url = server.url("/r416.bin").toString(),
                    preferRangeResume = true,
                    rangeStartInclusive = 1L
                ),
                sink
            )
        }
        sink.close()
        assertTrue(result is JmxResult.Success)
        assertEquals(200, (result as JmxResult.Success).value.statusCode)
        assertArrayEquals(full, Files.readAllBytes(path))
        assertEquals("bytes=1-", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }
}
