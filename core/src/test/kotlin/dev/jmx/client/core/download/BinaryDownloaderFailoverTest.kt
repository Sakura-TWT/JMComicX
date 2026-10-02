package dev.jmx.client.core.download

import dev.jmx.client.core.image.ImageHostRegistry
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class BinaryDownloaderFailoverTest {
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

    /** 后缀猜错（模板给 .jpg，实际存的是 .webp）应当自动换后缀重下，调用方无感。 */
    @Test
    fun notFoundFallsBackToTheNextSuffixOnTheSameHost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(imageResponse("webp-bytes"))
        val sink = MemoryByteSink()

        val result = downloader().download(imageRequest("00001.jpg"), sink)

        assertTrue(result is JmxResult.Success)
        assertEquals("webp-bytes", String(sink.bytes()))
        assertEquals(2, server.requestCount)
        assertEquals("/media/photos/500/00001.jpg", server.takeRequest().path)
        assertEquals("/media/photos/500/00001.webp", server.takeRequest().path)
    }

    /**
     * 转移过程对上层必须是不可见的：只有一次 Started、一次 Completed、零次 Failed，
     * 且地址始终是调用方给的那个——否则 UI 会以为"同一张图失败了又成功了"，
     * 或者按新地址当成另一张图去记进度。
     */
    @Test
    fun failoverIsInvisibleToTheObserver() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(imageResponse("webp-bytes"))
        val events = mutableListOf<DownloadEvent>()
        val originalUrl = server.url("/media/photos/500/00001.jpg").toString()

        downloader().download(
            imageRequest("00001.jpg", observer = { events += it }),
            MemoryByteSink()
        )

        assertEquals(1, events.count { it is DownloadEvent.Started })
        assertEquals(1, events.count { it is DownloadEvent.Completed })
        assertEquals(0, events.count { it is DownloadEvent.Failed })
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.url == originalUrl })
    }

    /**
     * 换机器前必须把上一台已写入的部分清掉，否则两段字节会拼成一个坏文件。
     * 这里第一台回 200 但零字节（线上 CDN 内部出错时的真实表现），第二台回正常内容。
     */
    @Test
    fun emptyBodyFailsOverToAnotherHostAndDiscardsPartialBytes() = runBlocking {
        server.enqueue(imageResponse(""))
        server.enqueue(imageResponse("real-bytes"))
        val sink = MemoryByteSink()

        // 127.0.0.1 与 localhost 是两个不同的主机名、同一台 MockWebServer：
        // 这样能真实走一遍换机器分支，又不会打到线上 CDN。
        val result = downloader(imageHosts = listOf("127.0.0.1", "localhost"))
            .download(imageRequest("00001.jpg"), sink)

        assertTrue(result is JmxResult.Success)
        assertEquals("real-bytes", String(sink.bytes()))
        assertEquals(2, server.requestCount)
        // 两次请求打的是同一台 MockWebServer，但 Host 必须换过——证明走的是换机器分支，
        // 而不是原地重试。（MockWebServer 用哪个名字对外不固定，所以只断言"变了"。）
        val firstHost = server.takeRequest().getHeader("Host")
        val secondHost = server.takeRequest().getHeader("Host")
        assertNotEquals(firstHost, secondHost)
    }

    /** 候补用尽后只报最后一次失败，且用原始地址。 */
    @Test
    fun exhaustedCandidatesReportASingleFailure() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
        val events = mutableListOf<DownloadEvent>()

        val result = downloader().download(
            imageRequest("00001.jpg", observer = { events += it }),
            MemoryByteSink()
        )

        assertTrue(result is JmxResult.Failure)
        // maxAttemptsPerDownload = 3：原地址 + .webp + .png
        assertEquals(3, server.requestCount)
        assertEquals(1, events.count { it is DownloadEvent.Failed })
    }

    /** A transient failure with no alternate host gets bounded same-address retry. */
    @Test
    fun noCandidateRetriesTheSameAddress() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(imageResponse("recovered"))

        val result = downloader(imageHosts = emptyList())
            .download(imageRequest("00001.jpg"), MemoryByteSink())

        assertTrue(result is JmxResult.Success)
        assertEquals(2, server.requestCount)
        assertEquals(server.takeRequest().path, server.takeRequest().path)
    }

    /**
     * 给了线路表就按健康度挑候补，而不是按内置表"当前机器之后那一台"轮转。
     * 这里 b 刚失败过，轮转会先试它（它就排在 a 后面），按健康度则应当跳到 c。
     */
    @Test
    fun failoverPrefersTheHealthiestHostFromTheRegistry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(imageResponse("bytes"))
        val registry = ImageHostRegistry(
            initialHosts = listOf("a.test", "b.test", "c.test"),
            maxFailuresBeforeDemote = 1
        )
        registry.markFailure("b.test", "HTTP 503")

        val result = registryDownloader(registry)
            .download(hostedImageRequest("a.test", "00001.jpg"), MemoryByteSink())

        assertTrue(result is JmxResult.Success)
        assertEquals("a.test", requestedHost())
        assertEquals("c.test", requestedHost())
    }

    /** 下载是图片流量的大头，成败要记回线路表，取图侧才能少踩一遍同样的坑。 */
    @Test
    fun reportsHostResultsToTheRegistry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(imageResponse("bytes"))
        val registry = ImageHostRegistry(
            initialHosts = listOf("a.test", "b.test"),
            maxFailuresBeforeDemote = 1
        )

        registryDownloader(registry)
            .download(hostedImageRequest("a.test", "00001.jpg"), MemoryByteSink())

        val failed = registry.all().single { it.host == "a.test" }
        val succeeded = registry.all().single { it.host == "b.test" }
        assertEquals(1, failed.failureCount)
        assertTrue("失败原因未被记下", failed.lastFailureMessage != null)
        assertEquals(1, succeeded.successCount)
    }

    /**
     * 后缀猜错不能记到机器头上。全部候补都 404 时那台机器一次也不该被降级——
     * 否则一本后缀不对的漫画就能把整张线路表逐个拉黑。
     */
    @Test
    fun doesNotBlameTheHostForAWrongSuffix() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
        val registry = ImageHostRegistry(
            initialHosts = listOf("a.test", "b.test"),
            maxFailuresBeforeDemote = 1
        )

        val result = registryDownloader(registry)
            .download(hostedImageRequest("a.test", "00001.jpg"), MemoryByteSink())

        assertTrue(result is JmxResult.Failure)
        val host = registry.all().single { it.host == "a.test" }
        assertEquals(0, host.failureCount)
        assertNull(host.unavailableUntilMillis)
    }

    private fun downloader(
        imageHosts: List<String> = listOf("127.0.0.1")
    ): BinaryDownloader = BinaryDownloader(
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        imageHosts = imageHosts
    )

    private fun registryDownloader(registry: ImageHostRegistry): BinaryDownloader = BinaryDownloader(
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            // 线路表里的域名统统解到本地的 MockWebServer 上，换机器才走得通又不打线上 CDN。
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build(),
        imageHostRegistry = registry
    )

    private fun hostedImageRequest(host: String, fileName: String): DownloadRequest = DownloadRequest(
        url = "http://$host:${server.port}/media/photos/500/$fileName",
        acceptedContentTypes = setOf("image/*")
    )

    private fun requestedHost(): String? =
        server.takeRequest().getHeader("Host")?.substringBefore(':')

    private fun imageRequest(
        fileName: String,
        observer: DownloadObserver = DownloadObserver.None
    ): DownloadRequest = DownloadRequest(
        url = server.url("/media/photos/500/$fileName").toString(),
        acceptedContentTypes = setOf("image/*"),
        observer = observer
    )

    private fun imageResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "image/webp")
        .setBody(body)
}
