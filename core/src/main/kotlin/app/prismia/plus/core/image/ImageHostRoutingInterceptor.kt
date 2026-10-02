package app.prismia.plus.core.image

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 图片请求的选路与换机拦截器。
 *
 * 做三件事，全部只针对 `/media/` 下的 GET：
 * 1. 把地址改写到 [ImageHostRegistry] 当前认为最好的那台 CDN——调用方拼 URL 时用的主机
 *    可能是几分钟前选的，期间那台已经劣化了；
 * 2. 记录每次请求的成败与首字节耗时，喂回健康度；
 * 3. 失败时就地换一台重发，不把错误抛给上层。
 *
 * 放在 OkHttp 这一层而不是调用方，是因为 Coil 的内存/磁盘缓存键在进入网络层之前就按
 * `ImageRequest.data` 算好了：这里改写主机对缓存完全透明，同一张图无论从哪台机器取回来
 * 都命中同一个缓存条目。反过来若在拼 URL 时换机器，缓存就会按机器碎成几份。
 *
 * @param maxAttempts 含首次在内的最大尝试次数。给 3 的理由与 [app.prismia.plus.core.download.BinaryDownloader]
 *   一致：每个失败候补最坏要等满一个超时，而用户正盯着这张图。
 */
class ImageHostRoutingInterceptor(
    private val registry: ImageHostRegistry,
    private val maxAttempts: Int = 3,
    private val nowNanos: () -> Long = { System.nanoTime() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.isRoutableImageRequest()) return chain.proceed(original)

        val tried = linkedSetOf<String>()
        var attempts = 0
        var lastFailure: IOException? = null
        while (attempts < maxAttempts.coerceAtLeast(1)) {
            val host = registry.candidates(tried).firstOrNull() ?: break
            tried += host
            attempts++
            val request = original.withHost(host)
            val startedAt = nowNanos()
            val response = try {
                chain.proceed(request)
            } catch (error: IOException) {
                registry.markFailure(host, error.message)
                lastFailure = error
                if (!canRetry(attempts, tried)) throw error
                continue
            }
            // 计到首字节为止：图片大小差着几十倍，算全量下载耗时是在比图的大小而不是线路的快慢。
            val latencyMillis = TimeUnit.NANOSECONDS.toMillis(nowNanos() - startedAt)
            if (response.isSuccessful) {
                registry.markSuccess(host, latencyMillis)
                return response
            }
            if (!shouldSwitchHost(response.code)) return response
            registry.markFailure(host, "HTTP ${response.code}")
            // 没有下一次机会就把这个响应原样交回去：上层要看得到真实状态码，
            // 而不是被我们改写成一个"网络错误"。
            if (!canRetry(attempts, tried)) return response
            response.close()
        }
        throw lastFailure ?: IOException("图片线路均不可用：${original.url}")
    }

    private fun canRetry(attempts: Int, tried: Set<String>): Boolean {
        return attempts < maxAttempts.coerceAtLeast(1) && registry.candidates(tried).isNotEmpty()
    }

    private fun Request.isRoutableImageRequest(): Boolean {
        if (method != "GET") return false
        if (!url.encodedPath.startsWith(MEDIA_PATH_PREFIX)) return false
        // 不认识的主机不碰：下载器与诊断工具会拿这个客户端打各种地址，
        // 把它们改写到 CDN 上只会把请求打歪。
        return registry.knows(url.host)
    }

    private fun Request.withHost(host: String): Request {
        if (url.host.equals(host, ignoreCase = true)) return this
        // 只换主机，不动 Referer 等请求头：CDN 不校验 Referer 与主机是否一致，
        // 而下载器的换机路径一直是这么做的，行为保持一致。
        return newBuilder().url(url.newBuilder().host(host).build()).build()
    }

    /**
     * 这个状态码值不值得换一台机器再试。
     *
     * 404/410 刻意不换：各 CDN 是同一份存储，某台没有的文件换一台也不会有，
     * 那是后缀猜错（由 [app.prismia.plus.core.download.ImageHostFailover] 在下载侧换后缀解决），
     * 换机只会白等三个超时。
     */
    private fun shouldSwitchHost(code: Int): Boolean {
        return code == 403 || code == 408 || code == 429 || code >= 500
    }

    private companion object {
        const val MEDIA_PATH_PREFIX = "/media/"
    }
}

/**
 * 图片专用客户端：在 [base] 之上换一个更宽的分发器，并装上选路拦截器。
 *
 * 为什么要单独一个分发器：OkHttp 默认 `maxRequestsPerHost = 5`，而封面和漫画页全部来自
 * **同一台** CDN——一屏十几张封面里永远只有 5 张在飞，其余在队列里干等，
 * 这在弱网下就是首屏封面一张一张往外蹦的直接原因。接口请求不能一起放宽（那是限流敏感的），
 * 所以给图片单独一个分发器；连接池仍由 [OkHttpClient.newBuilder] 沿用 [base] 的，不额外占资源。
 */
fun imageOkHttpClient(base: OkHttpClient, registry: ImageHostRegistry): OkHttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 12
    }
    return base.newBuilder()
        .dispatcher(dispatcher)
        .addInterceptor(ImageHostRoutingInterceptor(registry))
        .build()
}
