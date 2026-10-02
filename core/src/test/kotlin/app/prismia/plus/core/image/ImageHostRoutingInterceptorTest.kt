package app.prismia.plus.core.image

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ImageHostRoutingInterceptorTest {
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

    /** 5xx 换一台机器重发，对调用方是不可见的：它拿到的是 200。 */
    @Test
    fun switchesHostAfterServerError() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(imageResponse("bytes"))
        val registry = registry()

        val response = client(registry).newCall(imageRequest("first.test")).execute()

        assertEquals(200, response.code)
        assertEquals("bytes", response.body.string())
        assertEquals(2, server.requestCount)
        assertEquals("first.test", requestedHost())
        assertEquals("second.test", requestedHost())
    }

    /**
     * 网络层错误（这里用"收到请求但不回包"模拟超时）同样换机，而不是把 IOException 抛给上层。
     * 这是弱网下最常见的失败形态，也是原来图片请求唯一没有处理的一类。
     */
    @Test
    fun switchesHostAfterNetworkError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(imageResponse("bytes"))
        val registry = registry()

        val response = client(registry).newCall(imageRequest("first.test")).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        assertEquals(1, registry.all().single { it.host == "first.test" }.failureCount)
        assertEquals("second.test", registry.all().first { it.successCount > 0 }.host)
    }

    /**
     * 404 刻意不换机：各 CDN 是同一份存储，某台没有的文件换一台也不会有。
     * 那是后缀猜错（由 [app.prismia.plus.core.download.ImageHostFailover] 换后缀解决），
     * 换机只会白等几个超时。
     */
    @Test
    fun doesNotSwitchHostOnNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))
        val registry = registry()

        val response = client(registry).newCall(imageRequest("first.test")).execute()

        assertEquals(404, response.code)
        assertEquals(1, server.requestCount)
        assertEquals(0, registry.all().sumOf { it.failureCount })
    }

    /** 候补用尽时要把最后那个响应原样交回去，上层才看得到真实状态码。 */
    @Test
    fun returnsLastResponseWhenEveryHostFails() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        val registry = registry()

        val response = client(registry).newCall(imageRequest("first.test")).execute()

        assertEquals(503, response.code)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1, 1), registry.all().map { it.failureCount })
    }

    /** 调用方拼 URL 时选的那台可能已经劣化了，首发就该改写到当前最好的那台。 */
    @Test
    fun rewritesFirstAttemptToHealthiestHost() {
        server.enqueue(imageResponse("bytes"))
        val registry = registry(maxFailuresBeforeDemote = 1)
        registry.markFailure("first.test", "HTTP 503")

        val response = client(registry).newCall(imageRequest("first.test")).execute()

        assertEquals(200, response.code)
        assertEquals("second.test", requestedHost())
    }

    /** 成功要把首字节耗时喂回健康度，否则延迟永远是 null，选路只能看成败。 */
    @Test
    fun recordsLatencyOnSuccess() {
        server.enqueue(imageResponse("bytes"))
        val registry = registry()

        client(registry).newCall(imageRequest("first.test")).execute().close()

        val host = registry.all().single { it.host == "first.test" }
        assertEquals(1, host.successCount)
        assertTrue("延迟未被记录", host.averageLatencyMillis != null)
    }

    /** 非 /media/ 的地址不碰：下载器和诊断工具会拿这个客户端打各种地址。 */
    @Test
    fun leavesNonImageRequestsAlone() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val registry = registry(maxFailuresBeforeDemote = 1)
        registry.markFailure("first.test", "HTTP 503")

        val request = Request.Builder()
            .url("http://first.test:${server.port}/setting")
            .build()
        client(registry).newCall(request).execute().close()

        assertEquals("first.test", requestedHost())
    }

    /** 线路表之外的主机也不碰，否则会把请求打歪到 CDN 上。 */
    @Test
    fun leavesUnknownHostsAlone() {
        server.enqueue(imageResponse("bytes"))
        val registry = registry(maxFailuresBeforeDemote = 1)
        registry.markFailure("first.test", "HTTP 503")

        client(registry).newCall(imageRequest("outsider.test")).execute().close()

        assertEquals("outsider.test", requestedHost())
    }

    /** 手动钉住线路时不换机，用户选的那台失败了就该如实报错。 */
    @Test
    fun honoursManuallyPinnedHost() {
        server.enqueue(MockResponse().setResponseCode(503))
        val registry = registry()
        registry.useManualHost("second.test")

        val response = client(registry).newCall(imageRequest("first.test")).execute()

        assertEquals(503, response.code)
        assertEquals(1, server.requestCount)
        assertEquals("second.test", requestedHost())
    }

    /** 换机只动主机，Referer 等请求头必须原样带上——否则 CDN 直接 403。 */
    @Test
    fun keepsHeadersWhenSwitchingHost() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(imageResponse("bytes"))
        val registry = registry()
        val request = imageRequest("first.test").newBuilder()
            .header("Referer", "https://18comic.vip/")
            .build()

        client(registry).newCall(request).execute().close()

        server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("https://18comic.vip/", retried.getHeader("Referer"))
        assertEquals("second.test", retried.getHeader("Host")?.substringBefore(':'))
    }

    private fun registry(maxFailuresBeforeDemote: Int = 2): ImageHostRegistry {
        return ImageHostRegistry(
            initialHosts = listOf("first.test", "second.test"),
            maxFailuresBeforeDemote = maxFailuresBeforeDemote,
        )
    }

    /**
     * 把线路表里的域名统统解到本地的 MockWebServer 上，这样"换机"是真的走了一遍
     * OkHttp 的完整调用链（含拦截器与连接复用），而不是对着假的 Chain 打桩。
     */
    private fun client(registry: ImageHostRegistry): OkHttpClient {
        val loopback = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        return OkHttpClient.Builder()
            .dns(loopback)
            // 超时压到很短：换机用例靠"服务端不回包"来触发 IOException。
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .addInterceptor(ImageHostRoutingInterceptor(registry))
            .build()
    }

    private fun imageRequest(host: String): Request = Request.Builder()
        .url("http://$host:${server.port}/media/photos/500/00001.webp")
        .build()

    private fun imageResponse(body: String): MockResponse = MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "image/webp")

    private fun requestedHost(): String? =
        server.takeRequest().getHeader("Host")?.substringBefore(':')
}
