package dev.jmx.client

import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class DiscoveryKind(val title: String) { WEEKLY("每周必看"), RANKING("排行榜") }
internal enum class RankingPeriod(val title: String, val value: String) {
    DAY("日榜", JmxMagicConstants.TIME_TODAY), WEEK("周榜", JmxMagicConstants.TIME_WEEK),
    MONTH("月榜", JmxMagicConstants.TIME_MONTH), ALL("总榜", JmxMagicConstants.TIME_ALL),
}
internal data class RankingCategory(val id: String, val title: String)
internal val rankingCategories = listOf(
    RankingCategory(JmxMagicConstants.CATEGORY_ALL, "全部"),
    RankingCategory(JmxMagicConstants.CATEGORY_DOUJIN, "同人"),
    RankingCategory(JmxMagicConstants.CATEGORY_SINGLE, "单行本"),
    RankingCategory(JmxMagicConstants.CATEGORY_SHORT, "短篇"),
    RankingCategory(JmxMagicConstants.CATEGORY_HANMAN, "韩漫"),
    RankingCategory(JmxMagicConstants.CATEGORY_MEIMAN, "美漫"),
    RankingCategory(JmxMagicConstants.CATEGORY_ANOTHER, "其他"),
    RankingCategory(JmxMagicConstants.CATEGORY_DOUJIN_COSPLAY, "Cosplay"),
    RankingCategory(JmxMagicConstants.CATEGORY_3D, "3D"),
    RankingCategory(JmxMagicConstants.CATEGORY_ENGLISH_SITE, "英文站"),
)
internal sealed interface DiscoveryQuery {
    data class Weekly(val weekId: String, val typeId: String) : DiscoveryQuery
    data class Ranking(val period: RankingPeriod, val categoryId: String) : DiscoveryQuery
}
internal data class DiscoveryPage(val albums: List<HomeAlbum>, val total: Int?)
internal enum class DiscoveryEnd(val message: String) {
    EMPTY("暂无更多内容"), TOTAL("已到本轮列表末尾"),
    NO_PROGRESS("服务端返回重复页，已停止加载。可刷新重试。"),
    ITEM_LIMIT("已达到本轮内容加载上限。可刷新重新浏览。"),
    PAGE_LIMIT("已达到本轮分页上限。可刷新重新浏览。"),
}
internal data class DiscoveryFeedState(
    val query: DiscoveryQuery? = null,
    val albums: List<HomeAlbum> = emptyList(),
    val total: Int? = null,
    val nextPage: Int = 1,
    val firstLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val end: DiscoveryEnd? = null,
    val forceRefresh: Boolean = false,
)

/** Linear-time stable merge: never sort the server ranking or advance on a failed page. */
internal fun mergeDiscoveryPage(state: DiscoveryFeedState, page: DiscoveryPage, itemLimit: Int = 2_000, pageLimit: Int = 100): DiscoveryFeedState {
    if (state.nextPage == 1 && page.albums.isEmpty() && (page.total ?: 0) > 0) {
        return state.copy(loading = false, error = "列表为空但总数不为零，请刷新重试。")
    }
    val previous = if (state.nextPage == 1) emptyList() else state.albums
    val merged = LinkedHashMap<String, HomeAlbum>(minOf(itemLimit, previous.size + page.albums.size))
    previous.forEach { if (merged.size < itemLimit) merged.putIfAbsent(it.id, it) }
    page.albums.forEach { album -> if (album.id.isNotBlank() && merged.size < itemLimit) merged.putIfAbsent(album.id, album) }
    val total = page.total ?: if (state.nextPage == 1) null else state.total
    val end = when {
        page.albums.isEmpty() -> DiscoveryEnd.EMPTY
        merged.size == previous.size -> DiscoveryEnd.NO_PROGRESS
        total != null && merged.size >= total -> DiscoveryEnd.TOTAL
        merged.size >= itemLimit -> DiscoveryEnd.ITEM_LIMIT
        state.nextPage >= pageLimit -> DiscoveryEnd.PAGE_LIMIT
        else -> null
    }
    return state.copy(albums = merged.values.toList(), total = total, nextPage = state.nextPage + 1,
        firstLoaded = true, loading = false, error = null, end = end)
}

/** One active UI request, generation-isolated cancellation, bounded pages/items/time, no IO under lock. */
internal class DiscoveryFeedController(
    private val scope: CoroutineScope,
    private val loader: suspend (DiscoveryQuery, Int, Boolean) -> JmxResult<DiscoveryPage>,
    private val timeoutMillis: Long = 20_000,
    private val itemLimit: Int = 2_000,
    private val pageLimit: Int = 100,
    private val snapshotLimit: Int = 0,
    private val snapshotItemLimit: Int = 4_000,
    private val snapshotTtlMillis: Long = 60_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val coordinationLock: Any = Any(),
) {
    init { require(timeoutMillis > 0 && itemLimit > 0 && pageLimit > 0 && snapshotLimit >= 0 && snapshotItemLimit > 0 && snapshotTtlMillis >= 0) }
    private data class Cached(val state: DiscoveryFeedState, val storedAt: Long)
    private val snapshots = LinkedHashMap<DiscoveryQuery, Cached>()
    private var snapshotItems = 0
    private val lock = coordinationLock
    private var generation = 0L
    private var closed = false
    private var active: Job? = null
    private val mutable = MutableStateFlow(DiscoveryFeedState())
    val state: StateFlow<DiscoveryFeedState> = mutable.asStateFlow()

    fun select(query: DiscoveryQuery, fresh: Boolean = false) { prepareSelection(query, fresh)?.invoke() }

    /** Prepare under a shared coordinator lock, start/cancel only after the caller releases it. */
    internal fun prepareSelection(query: DiscoveryQuery, fresh: Boolean = false): (() -> Unit)? {
        val request = synchronized(lock) {
            if (closed || mutable.value.query == query && !fresh) null
            else {
                val cached = snapshots[query]?.takeIf { !fresh && nowMillis() - it.storedAt in 0 until snapshotTtlMillis }
                if (cached != null) {
                    generation++
                    val previous = active
                    active = null
                    snapshots.remove(query)
                    snapshots[query] = cached
                    mutable.value = cached.state
                    Prepared(previous, null)
                } else {
                    removeSnapshot(query)
                    prepare(query, reset = true, keep = mutable.value.query == query, fresh = fresh)
                }
            }
        }
        return request?.let { it::run }
    }
    internal fun prepareClear(): () -> Unit {
        val previous = synchronized(lock) {
            if (closed) null else {
                generation++
                val previous = active
                active = null
                snapshots.clear(); snapshotItems = 0
                mutable.value = DiscoveryFeedState()
                previous
            }
        }
        return { previous?.cancel() }
    }
    internal val cachedQueryCount: Int get() = synchronized(lock) { snapshots.size }
    internal val cachedAlbumCount: Int get() = synchronized(lock) { snapshotItems }
    fun refresh() {
        val request = synchronized(lock) { mutable.value.query?.takeIf { !closed }?.let { prepare(it, reset = true, keep = true, fresh = true) } }
        request?.run()
    }
    fun retry() {
        val request = synchronized(lock) {
            val state = mutable.value
            state.query?.takeIf { !closed && !state.loading && state.error != null }?.let { prepare(it, reset = false, keep = true, fresh = true) }
        }
        request?.run()
    }
    fun loadMore() {
        val request = synchronized(lock) {
            val state = mutable.value
            state.query?.takeIf { !closed && !state.loading && state.firstLoaded && state.error == null && state.end == null }
                ?.let { prepare(it, reset = false, keep = true, fresh = false) }
        }
        request?.run()
    }
    fun close() { prepareClose().invoke() }
    internal fun prepareClose(): () -> Unit {
        val previous = synchronized(lock) {
            closed = true; generation++
            snapshots.clear(); snapshotItems = 0
            val previous = active; active = null
            mutable.value = mutable.value.copy(loading = false)
            previous
        }
        return { previous?.cancel() }
    }

    private data class Prepared(val previous: Job?, val job: Job?) {
        fun run() { previous?.cancel(); job?.start() }
    }
    private fun removeSnapshot(query: DiscoveryQuery) {
        snapshots.remove(query)?.let { snapshotItems -= it.state.albums.size }
    }
    private fun rememberSnapshot(state: DiscoveryFeedState) {
        val query = state.query ?: return
        if (snapshotLimit == 0 || !state.firstLoaded || state.error != null) return
        removeSnapshot(query)
        snapshots[query] = Cached(state, nowMillis())
        snapshotItems += state.albums.size
        while (snapshots.size > snapshotLimit || snapshotItems > snapshotItemLimit) removeSnapshot(snapshots.keys.first())
    }
    private fun prepare(query: DiscoveryQuery, reset: Boolean, keep: Boolean, fresh: Boolean): Prepared {
        if (fresh) removeSnapshot(query)
        val ticket = ++generation
        val previous = active
        val before = if (keep) mutable.value else DiscoveryFeedState(query = query)
        val request = before.copy(query = query, nextPage = if (reset) 1 else before.nextPage, loading = true,
            error = null, end = if (reset) null else before.end, forceRefresh = if (reset) fresh else fresh || before.forceRefresh)
        mutable.value = request
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                withTimeoutOrNull(timeoutMillis) { loader(query, request.nextPage, request.forceRefresh) }
                    ?: JmxResult.Failure(JmxError.Network("请求超时，请重试。"))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { JmxResult.Failure(JmxError.Network("加载失败，请重试。")) }
            synchronized(lock) {
                if (!closed && generation == ticket) {
                    mutable.value = when (result) {
                        is JmxResult.Success -> mergeDiscoveryPage(request, result.value, itemLimit, pageLimit).also(::rememberSnapshot)
                        is JmxResult.Failure -> request.copy(loading = false, error = result.error.toUiMessage())
                    }
                }
            }
        }
        active = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (generation == ticket) { active = null; mutable.value = mutable.value.copy(loading = false) }
            }
        }
        return Prepared(previous, job)
    }
}
