package dev.jmx.client

import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

internal data class AlbumUpdateScanResult(val scanned: Int, val failed: Int, val pendingAlbums: Int, val completed: Boolean = failed == 0) {
    val attempted: Int get() = scanned + failed
}

internal interface AlbumUpdateObservations {
    fun records(): Map<String, AlbumUpdateRecord>
    fun observe(albumId: String, chapterCount: Int, at: Long)
}

/** Partial observations survive cancellation. Oldest observations are retried first. */
internal class AlbumUpdateScanner(
    private val store: AlbumUpdateObservations,
    private val loadChapterCount: suspend (String) -> Int?,
    private val roundTimeoutMillis: Long = 60_000L,
    private val itemTimeoutMillis: Long = 15_000L,
    private val observationTime: () -> Long = System::currentTimeMillis,
) {
    constructor(core: JmxCore, store: AlbumUpdateStore) : this(
        store = store,
        loadChapterCount = { id -> (core.albumApi.detailFull(id) as? JmxResult.Success)?.value?.readingChapters()?.size },
        observationTime = store::nextObservationTime,
    )

    suspend fun scan(albumIds: Collection<String>, onProgress: () -> Unit = {}): AlbumUpdateScanResult = withContext(Dispatchers.IO) {
        val records = store.records()
        val targets = albumIds.map(String::trim).filter(String::isNotEmpty).distinct()
            .sortedBy { records[it]?.checkedAt ?: 0L }
        val scanned = AtomicInteger(0)
        withTimeoutOrNull(roundTimeoutMillis) {
            coroutineScope {
                val cursor = java.util.concurrent.atomic.AtomicInteger()
                (0 until minOf(4, targets.size)).map { async {
                    while (true) {
                        val index = cursor.getAndIncrement()
                        if (index >= targets.size) break
                        val id = targets[index]
                        val observedAt = observationTime()
                        val count = try {
                            withTimeoutOrNull(itemTimeoutMillis) { loadChapterCount(id) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                        if (count != null && count >= 0) {
                            store.observe(id, count, observedAt)
                            scanned.incrementAndGet()
                            onProgress()
                        }
                    }
                } }.awaitAll()
            }
        }
        AlbumUpdateScanResult(scanned.get(), targets.size - scanned.get(), store.records().values.count(AlbumUpdateRecord::hasUpdate))
    }
}
