package dev.jmx.client.core.cache

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class JsonResponseCacheIndexTest {
    @Test fun repeatedWritesScanDirectoryOnlyOnceAndLockMemoryIsFixed() {
        val cache = JsonResponseCache(Files.createTempDirectory("r3-index"))
        repeat(300) { cache.write("$it", "{}") }
        assertEquals(1, cache.indexScanCount)
        repeat(300) { assertNotNull(cache.read("$it")) }
        val stripes = cache.javaClass.getDeclaredField("stripes").apply { isAccessible = true }.get(cache) as Array<*>
        assertEquals(64, stripes.size)
    }
    @Test fun overwritesRemoveAndClearKeepByteAccountingAccurate() {
        val dir = Files.createTempDirectory("r3-index")
        var now = 1000L; val cache = JsonResponseCache(dir, 100) { now++ }
        repeat(20) { cache.write("a", "{}") }
        cache.write("b", "{}"); assertNotNull(cache.read("a")); assertNotNull(cache.read("b"))
        cache.remove("a"); cache.write("c", "{}"); assertNull(cache.read("a")); assertNotNull(cache.read("c"))
        cache.clear(); assertNull(cache.read("b")); cache.write("d", "{}")
        assertEquals(2, cache.indexScanCount)
    }
    @Test fun recencyEvictionAndOversizeAdmissionKeepActualDiskBounded() {
        val dir = Files.createTempDirectory("r3-index"); var now = 10L
        val cache = JsonResponseCache(dir, 60) { now++ }
        cache.write("a", "a".repeat(20)); cache.write("b", "b".repeat(20)); cache.read("a")
        cache.write("c", "c".repeat(20))
        assertNotNull(cache.read("a")); assertNull(cache.read("b")); assertNotNull(cache.read("c"))
        cache.write("huge", "x".repeat(100)); assertNull(cache.read("huge"))
        val total = Files.list(dir).use { files -> files.mapToLong { Files.size(it) }.sum() }
        assertTrue(total <= 60)
    }
    @Test fun startupIndexIncludesExistingFilesAndGenerationInvalidationUpdatesIndex() {
        val dir = Files.createTempDirectory("r3-index")
        JsonResponseCache(dir).write("old", "{}", "old")
        val cache = JsonResponseCache(dir); cache.write("new", "{}", "new"); cache.invalidateOtherGenerations("new")
        assertNull(cache.read("old")); assertNotNull(cache.read("new")); assertEquals(1, cache.indexScanCount)
    }
    @Test fun clockRollbackAndOverflowCannotMakeCorruptEntriesFresh() {
        assertEquals(Long.MAX_VALUE, JsonResponseCache.Entry("{}", 100, "").ageMillis(99))
        assertEquals(Long.MAX_VALUE, JsonResponseCache.Entry("{}", Long.MIN_VALUE, "").ageMillis(100))
    }
}
