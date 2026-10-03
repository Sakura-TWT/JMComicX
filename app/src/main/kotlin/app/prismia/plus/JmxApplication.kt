package app.prismia.plus

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JmxApplication : Application(), SingletonImageLoader.Factory {
    /** Shared composition root for comic/video sources and their isolated sessions. */
    val prismiaContainer: PrismiaAppContainer by lazy { PrismiaAppContainer(this) }

    private var managedImageLoader: ImageLoader? = null
    private val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Warm the encrypted session off the main thread. Network transports
        // receive a token immediately after startup without blocking rendering.
        backendScope.launch {
            runCatching { prismiaContainer.videoSessionManager.load() }
                .onFailure { failure -> Log.w(TAG, "video session warm-up failed", failure) }
            runCatching { prismiaContainer.migrateLegacyVideoContentStore() }
                .onFailure { failure -> Log.w(TAG, "video content migration deferred", failure) }
        }
    }

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
        // 只在系统真的要不回内存时才整体清空。
        // 原判据是 level >= TRIM_MEMORY_UI_HIDDEN(20)，而 UI_HIDDEN 只表示"界面不可见了"——
        // 用户按一下 Home 再切回来，全部封面都要重新解码，这是最常见的路径上最贵的一次浪费。
        // RUNNING_CRITICAL(15) 与 COMPLETE(80) 才是"再不放就要被杀"的信号。
        val mustEvictEverything = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (mustEvictEverything) {
            memoryCache.clear()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Coil 3 去掉了按 level 分档的 trimMemory，只留下"缩到多大"。
            // 中等压力下砍掉一半：最近看过的页还在，代价是回翻更远时重新解码。
            memoryCache.trimToSize(memoryCache.size / 2)
        }
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

private const val TAG = "PrismiaBackend"
