package dev.jmx.client

import android.content.Context
import dev.jmx.client.core.network.backgroundNetwork
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 是否显示设置页里的「漫画更新提示」测试区块（立即检查 / 模拟一次 / 清除全部）。
 *
 * 这三个入口只为本地自测而设，正式发布的设置页不出现：debug 为 true，release 编译期为 false
 * （见 `app/build.gradle.kts` 的 `COMIC_UPDATE_HINTS`）。注意它只控制"测试入口的显隐"，
 * 更新提示功能本身在正式版里照常工作。
 *
 * 声明为 `const val` 而非普通 `val`：只有编译期常量才能让引用处的 `if` 被常量折叠，
 * 使 release 包里连这几个测试按钮的字串都不残留；运行时分支做不到这一点。
 */
internal const val SHOW_COMIC_UPDATE_TEST_ENTRIES: Boolean = BuildConfig.COMIC_UPDATE_HINTS

/**
 * 更新提示的单一数据源。
 *
 * "我的"页的角标、收藏页的"更新 N 章"、书架分组栏的红点读的都是同一份 [records]，
 * 所以"点开漫画 → 提示消失"天然地在三处同时生效，不需要各页面各自记一套已读状态
 * （那正是"全部分组和自建分组红点不同步"的经典成因）。
 */
internal class AlbumUpdateCenter(
    context: Context,
    core: JmxCore,
    private val bookshelfRepository: BookshelfRepository,
    private val accountDataRepository: AccountDataRepository,
    private val scope: CoroutineScope,
) {
    private val store = AlbumUpdateStore(context)
    private val scanner = AlbumUpdateScanner(core, store)
    private val publishLock = Any()
    private var publishedAt = 0L
    private var lastAttemptAt = 0L
    private var lastAttemptScope: String? = null

    private val _records = MutableStateFlow<Map<String, AlbumUpdateRecord>>(emptyMap())
    val records: StateFlow<Map<String, AlbumUpdateRecord>> = _records.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 上一次成功枚举到的收藏成员。角标只数这里面的更新，书架独有漫画不算。 */
    private val _favoriteAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteAlbumIds: StateFlow<Set<String>> = _favoriteAlbumIds.asStateFlow()

    private val queue = AlbumUpdateScanQueue(scope, { _scanning.value = it }) { includeFavorites -> backgroundNetwork { scanRound(includeFavorites) } }

    suspend fun loadSnapshot() = withContext(Dispatchers.IO) {
        publish()
        val identity = accountDataRepository.accountIdentity()
        _favoriteAlbumIds.value = if (identity != null && store.favoriteOwner() == identity) store.favoriteAlbumIds() else emptySet()
    }

    fun pendingAlbumCount(): Int = _records.value.values.count(AlbumUpdateRecord::hasUpdate)

    /** "我的 → 漫画收藏"角标：收藏成员里有几部在更新。 */
    fun pendingFavoriteCount(): Int = countPendingFavorites(_records.value, _favoriteAlbumIds.value)

    fun pendingChapters(albumId: String): Int =
        _records.value[albumId.trim()]?.pendingChapters ?: 0

    /** 距上次扫描够久了吗。用来把"进页面就扫"压成"隔一段时间才扫"。 */
    fun isStale(now: Long = System.currentTimeMillis(), includeFavorites: Boolean = false): Boolean {
        val requestedScope = scanScope(includeFavorites)
        val retryAllowed = requestedScope != lastAttemptScope || now - lastAttemptAt >= 30_000L
        return retryAllowed && (store.lastScanScope() != requestedScope || now - store.lastScanAt() >= MIN_SCAN_INTERVAL_MILLIS)
    }

    private fun scanScope(includeFavorites: Boolean): String =
        if (includeFavorites) accountDataRepository.accountIdentity()?.let { "favorites:$it" } ?: "bookshelf" else "bookshelf"

    /**
     * 用户点开了某部漫画：提示立即消失。
     *
     * [chapterCount] 有值时说明详情页已经拿到最新章节列表，顺手把最新话数一起对账，
     * 避免"扫描说 +2、详情其实 +3、退出来还剩 +1"。
     */
    fun markSeen(albumId: String, chapterCount: Int? = null) {
        if (chapterCount == null) {
            store.markSeen(albumId)
        } else {
            store.markSeenWithChapterCount(albumId, chapterCount)
        }
        publish()
    }

    /**
     * 扫一轮更新。
     *
     * [force] 为 false 时受 [isStale] 约束，避免每次切页面都打一轮 `/album`。
     * 登录态下把收藏也纳入扫描范围：书架条目大多来自收藏，但收藏里可能有还没进书架的漫画，
     * 而"我的 → 漫画收藏"的角标说的就是收藏。
     */
    suspend fun scan(includeFavorites: Boolean, force: Boolean): AlbumUpdateScanResult? {
        if (!force && !isStale(includeFavorites = includeFavorites)) return null
        return queue.request(includeFavorites)
    }

    private suspend fun scanRound(includeFavorites: Boolean): AlbumUpdateScanResult = withContext(Dispatchers.IO) {
        val identity = if (includeFavorites) accountDataRepository.accountIdentity() else null
        val requestedScope = scanScope(includeFavorites)
        lastAttemptAt = System.currentTimeMillis()
        lastAttemptScope = requestedScope
        val targets = bookshelfRepository.entries().mapTo(LinkedHashSet()) { it.albumId }
        if (identity == null || store.favoriteOwner() != identity) _favoriteAlbumIds.value = emptySet()
        var partial: AlbumUpdateScanResult? = null
        val round = withTimeoutOrNull(90_000L) {
            val favorites = if (identity != null) collectFavoriteAlbumIds() else null
            val membershipComplete = identity == null || (favorites != null && accountDataRepository.accountIdentity() == identity)
            if (favorites != null && accountDataRepository.accountIdentity() == identity) {
                store.setFavoriteAlbumIds(favorites)
                store.setFavoriteOwner(identity!!)
                _favoriteAlbumIds.value = favorites
                targets += favorites
            } else if (identity != null && accountDataRepository.accountIdentity() == identity && store.favoriteOwner() == identity) {
                targets += store.favoriteAlbumIds()
            }
            if (membershipComplete) {
                val retainedFavorites = store.favoriteAlbumIds()
                store.retainOnly(targets + retainedFavorites)
            }
            val scanned = scanner.scan(targets) { publish(force = false) }
            partial = scanned
            if (scanned.failed == 0 && membershipComplete && scanScope(includeFavorites) == requestedScope) {
                store.setLastScanAt(System.currentTimeMillis())
                store.setLastScanScope(requestedScope)
            }
            scanned.copy(completed = scanned.completed && membershipComplete)
        }
        publish()
        round ?: (partial ?: AlbumUpdateScanResult(0, targets.size, store.pendingAlbumCount())).copy(completed = false)
    }

    suspend fun acknowledgeBookshelf(snapshot: Map<String, AlbumUpdateRecord>) = withContext(Dispatchers.IO) {
        store.acknowledge(snapshot)
        publish()
    }
    /**
     * 调试用：伪造更新。
     *
     * 真实更新等不来（参见 [AlbumUpdateStore.simulateUpdates] 的注释），
     * 所以从书架里取前几部漫画把基线往回调，让整条链路（红点 → 角标 → 点开消失）可验证。
     */
    fun simulateUpdates(albums: Int, chapters: Int): Int {
        if (albums <= 0) return 0
        // 收藏优先：这样"我的 → 漫画收藏"的角标也能被验证到，不至于只有书架标签变色。
        // 未登录时收藏集合为空，自然退化成只模拟书架漫画。
        val ordered = LinkedHashSet<String>()
        ordered += store.favoriteAlbumIds()
        bookshelfRepository.entries().forEach { ordered += it.albumId }
        val affected = store.simulateUpdates(ordered.take(albums), chapters)
        publish()
        return affected
    }

    fun clearAllPending() {
        store.clearAllPending()
        publish()
    }

    private fun publish(force: Boolean = true) {
        synchronized(publishLock) {
            val now = System.nanoTime()
            if (force || now - publishedAt >= 200_000_000L) {
                _records.value = store.records()
                publishedAt = now
            }
        }
    }

    /**
     * 枚举全部收藏 id。
     *
     * 返回 null 表示"没能拿到"（未登录、请求失败、响应畸形），与"收藏是空集合"区分开——
     * 调用方据此决定要不要把它当作完整的关注集合。中途某一页失败也算拿不到：
     * 半份收藏列表比没有更危险。
     */
    private suspend fun collectFavoriteAlbumIds(): Set<String>? {
        val identity = accountDataRepository.accountIdentity() ?: return null
        val ids = LinkedHashSet<String>()
        var page = 1
        while (page <= MAX_FAVORITE_PAGES) {
            val result = accountDataRepository.loadCollection(
                kind = AccountCollectionKind.FAVORITES,
                page = page,
            )
            val value = (result as? JmxResult.Success)?.value ?: return null
            if (accountDataRepository.accountIdentity() != identity) return null
            if (value.albums.isEmpty()) return ids
            val before = ids.size
            value.albums.forEach { ids += it.id }
            if (ids.size == before) return null
            val total = value.total
            if (total != null && ids.size >= total) return ids
            page++
        }
        return null
    }
}

/**
 * 两次自动扫描之间的最小间隔。
 *
 * 一轮扫描是"每部漫画一次 `/album`"，不能随页面切换反复触发；
 * 用户想立刻知道结果时有书架页的下拉刷新（force = true）兜着。
 */
private const val MIN_SCAN_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

/** 收藏枚举的页数上限，纯粹防御畸形响应导致的死循环（每页 20，够到 2000 部）。 */
private const val MAX_FAVORITE_PAGES = 100
