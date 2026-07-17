package dev.jmx.client

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache

class JmxApplication : Application(), ImageLoaderFactory {
    private var managedImageLoader: ImageLoader? = null

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(IMAGE_MEMORY_CACHE_PERCENT)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIRECTORY))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
            .also { managedImageLoader = it }
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val memoryCache = managedImageLoader?.memoryCache ?: return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            memoryCache.clear()
        } else {
            memoryCache.trimMemory(level)
        }
    }

    override fun onLowMemory() {
        managedImageLoader?.memoryCache?.clear()
        super.onLowMemory()
    }
}

internal const val IMAGE_CACHE_DIRECTORY = "image_cache"
internal const val IMAGE_DISK_CACHE_MAX_BYTES = 96L * 1024L * 1024L
internal const val IMAGE_MEMORY_CACHE_PERCENT = 0.18
