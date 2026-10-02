package app.prismia.plus.core.download

import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

class BinaryDownloaderReliabilityTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun cleanup() { server.shutdown() }

    @Test fun transientRetryHasFiniteBudgetAndOneTerminalEvent() = runBlocking {
        // 503 + Retry-After: 0 triggers OkHttp's own follow-up before the downloader sees it.
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
        val events = mutableListOf<DownloadEvent>()
        val result = downloader().download(request(events::add), MemoryByteSink())
        assertTrue(result is JmxResult.Failure)
        assertEquals(3, server.requestCount)
        assertEquals(1, events.count { it is DownloadEvent.Started })
        assertEquals(1, events.count { it is DownloadEvent.Failed })
        assertEquals(0, events.count { it is DownloadEvent.Completed })
    }

    @Test fun longServerBackoffStopsRatherThanRetryingEarlierThanRequested() = runBlocking {
        for (code in listOf(429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "60"))
            val result = withTimeout(2_000) { downloader().download(request(), MemoryByteSink()) } as JmxResult.Failure
            assertEquals(60_000L, (result.error as JmxError.Http).retryAfterMillis)
        }
        assertEquals(2, server.requestCount) // Exactly one request per download, no shortened wait.
    }

    @Test fun partialNetworkFailureTruncatesBeforeRetryAndDoesNotDuplicateBytes() = runBlocking {
        server.enqueue(image("x".repeat(64 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(image("complete"))
        val sink = MemoryByteSink()
        val result = downloader().download(request(), sink)
        assertTrue(result is JmxResult.Success)
        assertEquals("complete", String(sink.bytes()))
        assertEquals(2, server.requestCount)
    }

    @Test fun storageIOExceptionIsNeverRetriedOrClassifiedAsNetwork() = runBlocking {
        server.enqueue(image("bytes"))
        var resets = 0
        val sink = object : ByteSink, TruncatingSink {
            override fun write(bytes: ByteArray) { throw IOException("synthetic storage full") }
            override fun truncate() { resets++ }
        }
        val result = downloader().download(request(), sink) as JmxResult.Failure
        assertTrue(result.error is JmxError.Unknown)
        assertTrue(result.error.cause is DownloadSinkException)
        assertFalse(result.error.retryable)
        assertEquals(1, server.requestCount)
        assertEquals(0, resets)
    }

    @Test fun permanentStatusAndOversizeValidationDoNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(downloader().download(request(), MemoryByteSink()) is JmxResult.Failure)
        server.enqueue(image("too-large"))
        assertTrue(downloader().download(request().copy(maxBytes = 2), MemoryByteSink()) is JmxResult.Failure)
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationClosesBlockedResponseBodyWithoutWaitingForReadTimeout() = runBlocking {
        server.enqueue(image("ab").throttleBody(1, 30, TimeUnit.SECONDS))
        val firstByte = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<DownloadEvent>())
        val sink = object : ByteSink {
            override fun write(bytes: ByteArray) { firstByte.complete(Unit) }
        }
        val task = async { downloader().download(request { events += it }, sink) }
        withTimeout(5_000) { firstByte.await() }
        withTimeout(2_000) { task.cancelAndJoin() }
        assertTrue(task.isCancelled)
        assertEquals(1, server.requestCount)
        assertFalse(events.any { it is DownloadEvent.Completed || it is DownloadEvent.Failed })
    }

    @Test fun cancellationClosesBlockedHeadersAndDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = async { downloader().download(request(), MemoryByteSink()) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        withTimeout(2_000) { task.cancelAndJoin() }
        assertTrue(task.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringBackoffDoesNotIssueAnotherRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))
        val responseClosed = CompletableDeferred<Unit>()
        val observedClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) { responseClosed.complete(Unit) }
        }).build()
        val task = async { BinaryDownloader(observedClient, imageHosts = emptyList()).download(request(), MemoryByteSink()) }
        withTimeout(5_000) { responseClosed.await() }
        withTimeout(2_000) { task.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }

    @Test fun sliceSinkReceivesOneReusableBufferWithoutPerWriteCopies() = runBlocking {
        val expected = ByteArray(25) { it.toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(expected)))
        val output = ByteArrayOutputStream()
        var first: ByteArray? = null
        var calls = 0
        val sink = object : ByteSink {
            override fun write(bytes: ByteArray) { error("copying overload must not be used") }
            override fun write(bytes: ByteArray, offset: Int, byteCount: Int) {
                if (first == null) first = bytes else assertSame(first, bytes)
                calls++
                output.write(bytes, offset, byteCount)
            }
        }
        val result = BinaryDownloader(client, bufferSize = 4).download(request(), sink)
        assertTrue(result is JmxResult.Success)
        assertTrue(calls > 1)
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun retryPolicyOnlyRetriesTransientErrorsWithBoundedBackoff() {
        assertTrue(DownloadRetry.transient(JmxError.Network("synthetic")))
        assertFalse(DownloadRetry.transient(JmxError.Network("synthetic", retryable = false)))
        assertFalse(DownloadRetry.transient(JmxError.Network("synthetic", javax.net.ssl.SSLHandshakeException("certificate"))))
        listOf(401, 403, 404, 410, 501, 505).forEach { assertFalse(DownloadRetry.transient(JmxError.Http(it, "synthetic"))) }
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue(DownloadRetry.transient(JmxError.Http(it, "synthetic"))) }
        assertFalse(DownloadRetry.transient(JmxError.Schema("synthetic")))
        assertEquals(250L, DownloadRetry.delayMillis(JmxError.Network("synthetic"), 1))
        assertEquals(500L, DownloadRetry.delayMillis(JmxError.Network("synthetic"), 2))
        assertEquals(999_999_999_000L, DownloadRetry.retryAfter("999999999"))
        assertEquals(2_000L, DownloadRetry.delayMillis(JmxError.Http(429, "synthetic", retryAfterMillis = 2_000), 1))
        assertFalse(DownloadRetry.transient(JmxError.Http(503, "synthetic", retryAfterMillis = 30_000)))
        assertEquals(3_000L, DownloadRetry.retryAfter("Thu, 1 Jan 1970 00:00:03 GMT", nowMillis = 0))
        assertNull(DownloadRetry.retryAfter("not-a-date"))
    }

    private fun downloader() = BinaryDownloader(client, imageHosts = emptyList())
    private fun image(body: String) = MockResponse().setHeader("Content-Type", "image/png").setBody(body)
    private fun request(observer: DownloadObserver = DownloadObserver.None) =
        DownloadRequest(server.url("/synthetic.bin").toString(), observer = observer)
}
