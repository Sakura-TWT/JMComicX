package app.prismia.plus

import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.runtime.JmxCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class AlbumUpdateScanResult(
    val scanned: Int,
    val failed: Int,
    val pendingAlbums: Int,
) {
    val attempted: Int get() = scanned + failed
}

/**
 * 更新扫描器。
 *
 * 为什么必须逐部拉 `/album`：收藏列表和书架里都只有 id/name/author/imageCount，
 * 服务端没有给任何"最后更新时间"或"章节数"字段（Python 参考实现里 `addtime` 只出现在评论上），
 * 所以"更新了几话"只能靠 `/album` 的章节列表长度和本地基线比出来。
 *
 * 代价用两件事压住：并发上限 [SCAN_CONCURRENCY]，以及调用方的最小扫描间隔。
 */
internal class AlbumUpdateScanner(
    private val core: JmxCore,
    private val store: AlbumUpdateStore,
) {
    /**
     * 扫一批漫画。
     *
     * [albumIds] 由调用方决定范围（书架条目 ∪ 收藏），这里不关心它们从哪来。
     * 单部失败只记一笔 [AlbumUpdateScanResult.failed]，不影响其余部分——
     * 网络抖一下就把整轮扫描判死会让红点时有时无。
     */
    suspend fun scan(albumIds: Collection<String>): AlbumUpdateScanResult = withContext(Dispatchers.IO) {
        val targets = albumIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (targets.isEmpty()) {
            store.setLastScanAt(System.currentTimeMillis())
            return@withContext AlbumUpdateScanResult(0, 0, store.pendingAlbumCount())
        }
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val counts = coroutineScope {
            targets.map { albumId ->
                async {
                    semaphore.withPermit {
                        albumId to chapterCountOf(albumId)
                    }
                }
            }.awaitAll()
        }
        val resolved = counts.mapNotNull { (id, count) -> count?.let { id to it } }.toMap()
        store.observeAll(resolved)
        store.setLastScanAt(System.currentTimeMillis())
        AlbumUpdateScanResult(
            scanned = resolved.size,
            failed = targets.size - resolved.size,
            pendingAlbums = store.pendingAlbumCount(),
        )
    }

    private suspend fun chapterCountOf(albumId: String): Int? =
        when (val result = core.albumApi.detailFull(albumId)) {
            is JmxResult.Success -> result.value.readingChapters().size
            is JmxResult.Failure -> null
        }
}

/** 并发上限。JM 对并发敏感，这里取和详情页统计页数相同的量级，别把服务端惹毛。 */
private const val SCAN_CONCURRENCY = 4
