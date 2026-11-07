package dev.jmx.client

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

class JmxApplication : Application(), SingletonImageLoader.Factory {
    private var managedImageLoader: ImageLoader? = null

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, IMAGE_MEMORY_CACHE_PERCENT)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIRECTORY).toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .components {
                // Coil 3 不再内置网络加载器，必须显式装一个；顺带把取图接到 core 的图片客户端上——
                // 于是封面与漫画页也享有按健康度选线路、失败自动换机（见 ImageHostRoutingInterceptor），
                // 并与业务请求共用连接池与 DNS 缓存。
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { createAppJmxCore(this@JmxApplication).imageHttpClient },
                    ),
                )
            }
            // Coil 3 默认就不理服务端的缓存头（等价于 Coil 2 的 respectCacheHeaders(false)），
            // 因此不再需要显式关闭。
            .crossfade(false)
            .build()
            .also { managedImageLoader = it }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val memoryCache = managedImageLoader?.memoryCache ?: return
        trimImageMemoryCache(memoryCache, level)
    }

    override fun onLowMemory() {
        managedImageLoader?.memoryCache?.clear()
        super.onLowMemory()
    }
}

internal const val IMAGE_CACHE_DIRECTORY = "image_cache"

/**
 * 磁盘缓存上限。漫画一话就是几十到上百张图，96MB 大约只装得下两三话，
 * 回头重看时几乎必然重新下载。放到 256MB 后常读的几话能真正留在本地；
 * 它在 cacheDir 下，磁盘吃紧时系统可回收，不会长期占着用户空间。
 */
internal const val IMAGE_DISK_CACHE_MAX_BYTES = 256L * 1024L * 1024L

/**
 * 内存缓存占可用堆的比例。阅读器连续翻页时前后若干页都应常驻，
 * 0.18 在长图章节里会频繁把刚翻过的页挤出去（回翻一页就要重新解码）。
 */
internal const val IMAGE_MEMORY_CACHE_PERCENT = 0.25
