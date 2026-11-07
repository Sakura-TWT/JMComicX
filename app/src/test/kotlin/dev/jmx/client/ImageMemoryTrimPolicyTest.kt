package dev.jmx.client

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ImageMemoryTrimPolicyTest {
    private fun encodedPages(): List<ByteArray> = List(8) { index ->
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff000000.toInt() or (index * 0x00102030))
        ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    private fun cache(pages: List<ByteArray>): MemoryCache = MemoryCache.Builder()
        .maxSizeBytes(256L * 1024)
        .weakReferencesEnabled(false)
        .build().also { cache ->
            pages.forEachIndexed { index, bytes ->
                cache[MemoryCache.Key("page:$index")] = MemoryCache.Value(decode(bytes).asImage())
            }
        }

    // Exact pre-R4 pressure branches. Fixture/cache/decoder are identical in both arms.
    private fun legacyTrim(cache: MemoryCache, level: Int) {
        when {
            level == 15 || level >= 80 -> cache.clear()
            level >= 10 -> cache.trimToSize(cache.size / 2)
        }
    }

    private fun replay(cache: MemoryCache, pages: List<ByteArray>): Map<String, Int> {
        var hits = 0
        var decodes = 0
        pages.forEachIndexed { index, bytes ->
            val key = MemoryCache.Key("page:$index")
            if (cache[key] != null) hits++ else {
                val bitmap = decode(bytes)
                val reference = decode(bytes)
                assertTrue(bitmap.sameAs(reference))
                reference.recycle()
                cache[key] = MemoryCache.Value(bitmap.asImage())
                decodes++
            }
        }
        return mapOf("memoryHits" to hits, "consumerDecodes" to decodes,
            "verificationDecodesExcluded" to decodes, "network" to 0)
    }

    @Test fun actualCacheCallsHitsAndEquivalentDecodesCoverAllTrimTiers() {
        val pages = encodedPages()
        val rows = (listOf(10, 15, 20, 40, 60, 80).map { listOf(it) } + listOf(listOf(20, 20, 20))).map { levels ->
            fun scenario(fixed: Boolean): Map<String, Any> {
                val cache = Mockito.spy(cache(pages))
                val before = cache.size
                Mockito.clearInvocations(cache)
                levels.forEach { level -> if (fixed) trimImageMemoryCache(cache, level) else legacyTrim(cache, level) }
                val after = cache.size
                val calls = Mockito.mockingDetails(cache).invocations
                val trims = calls.count { it.method.name == "trimToSize" }
                val clears = calls.count { it.method.name == "clear" }
                when (levels.first()) {
                    15, 80 -> { assertEquals(0L, after); assertEquals(levels.size, clears) }
                    20 -> if (fixed) { assertEquals(before, after); assertEquals(0, trims); assertEquals(0, clears) }
                    else -> { assertTrue(after <= before / 2); assertEquals(levels.size, trims) }
                }
                if (!fixed && levels.first() == 20) assertEquals(levels.size, trims)
                val work = replay(cache, pages)
                assertEquals(8, work.getValue("memoryHits") + work.getValue("consumerDecodes"))
                if (fixed && levels.first() == 20) assertEquals(8, work["memoryHits"])
                cache.clear()
                return mapOf("initialBytes" to before, "afterTrimBytes" to after,
                    "actualTrimCalls" to trims, "actualClearCalls" to clears, "onReturn" to work)
            }
            val before = scenario(false)
            val after = scenario(true)
            if (levels.first() != 20) assertEquals(before, after)
            mapOf("levels" to levels, "beforeSourceEquivalent" to before, "afterActualPolicy" to after)
        }
        assertEquals(0.25, IMAGE_MEMORY_CACHE_PERCENT, 0.0)
        assertEquals(256L * 1024 * 1024, IMAGE_DISK_CACHE_MAX_BYTES)
        savePerformanceEvidence("memory-trim", mapOf("rows" to rows,
            "fixture" to "8 frozen 64x64 ARGB PNGs; actual Coil strong MemoryCache, weak references disabled",
            "decoder" to "actual BitmapFactory; not full Coil reader restore pipeline",
            "pixelEquivalent" to true, "androidFramesMeasured" to false,
            "productionMemoryCachePercent" to IMAGE_MEMORY_CACHE_PERCENT,
            "productionDiskCacheMaxBytes" to IMAGE_DISK_CACHE_MAX_BYTES))
    }

    @Test fun applicationConsumerAndOnLowMemoryUseActualCache() {
        val cache = Mockito.spy(cache(encodedPages()))
        val loader = Mockito.mock(ImageLoader::class.java)
        Mockito.`when`(loader.memoryCache).thenReturn(cache)
        val application = JmxApplication()
        JmxApplication::class.java.getDeclaredField("managedImageLoader").apply { isAccessible = true }.set(application, loader)
        val before = cache.size
        Mockito.clearInvocations(cache)
        repeat(3) { application.onTrimMemory(20) }
        assertEquals(before, cache.size)
        Mockito.verify(cache, Mockito.never()).trimToSize(Mockito.anyLong())
        Mockito.verify(cache, Mockito.never()).clear()
        application.onLowMemory()
        Mockito.verify(cache).clear()
        assertEquals(0L, cache.size)
    }
}
