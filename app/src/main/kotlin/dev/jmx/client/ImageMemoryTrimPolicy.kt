package dev.jmx.client

import android.content.ComponentCallbacks2
import coil3.memory.MemoryCache

internal fun trimImageMemoryCache(cache: MemoryCache, level: Int) {
    when {
        level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Unit
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.clear()
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cache.size / 2)
    }
}
