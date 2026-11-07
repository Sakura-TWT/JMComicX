package dev.jmx.client

import dev.jmx.client.core.api.WeekCategory
import dev.jmx.client.core.api.WeekInfo
import dev.jmx.client.core.api.WeekType
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class WeeklyDiscoveryState(
    val catalog: WeekInfo? = null,
    val weekId: String = "",
    val typeId: String = "",
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val refreshing: Boolean = false,
) {
    val week: WeekCategory? get() = catalog?.categories?.firstOrNull { it.id == weekId }
    val type: WeekType? get() = catalog?.types?.firstOrNull { it.id == typeId }
    val query: DiscoveryQuery.Weekly? get() = week?.let { w -> type?.let { DiscoveryQuery.Weekly(w.id, it.id) } }

    fun reconcile(info: WeekInfo): WeeklyDiscoveryState = copy(
        catalog = info,
        weekId = info.categories.firstOrNull { it.id == weekId }?.id ?: info.categories.firstOrNull()?.id.orEmpty(),
        typeId = info.types.firstOrNull { it.id == typeId }?.id ?: info.types.firstOrNull()?.id.orEmpty(),
    )
}

/**
 * Catalog + feed selection have ONE coordinator, not competing Compose effects.
 * A known query refreshes in parallel with metadata exactly once. Metadata may dispatch a
 * different query only if its winning result invalidates the current selection.
 * Both state machines share a short preparation lock; ALL job starts/cancels and IO run outside.
 */
internal class WeeklyDiscoveryCoordinator(
    private val scope: CoroutineScope,
    private val catalogLoader: suspend (Boolean) -> JmxResult<WeekInfo>,
    pageLoader: suspend (DiscoveryQuery, Int, Boolean) -> JmxResult<DiscoveryPage>,
    initialCatalog: WeekInfo? = null,
    initialWeekId: String = "",
    initialTypeId: String = "",
    private val timeoutMillis: Long = 20_000,
) {
    init { require(timeoutMillis > 0) }
    private val lock = Any()
    val feed = DiscoveryFeedController(scope, pageLoader, timeoutMillis = timeoutMillis, snapshotLimit = 4, coordinationLock = lock)
    private val mutable = MutableStateFlow(
        WeeklyDiscoveryState(weekId = initialWeekId, typeId = initialTypeId).let { initialCatalog?.let(it::reconcile) ?: it },
    )
    val state: StateFlow<WeeklyDiscoveryState> = mutable.asStateFlow()
    private var epoch = 0L
    private var closed = false
    private var activated = false
    private var catalogJob: Job? = null
    private val monitor = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        feed.state.collect { synchronized(lock) { finishRefreshIfIdle() } }
    }
    init { monitor.invokeOnCompletion { close() } }

    fun activate() {
        val actions = synchronized(lock) {
            if (closed || activated) null else {
                activated = true
                val page = mutable.value.query?.let { feed.prepareSelection(it) }
                page to prepareCatalog(fresh = false)
            }
        }
        actions?.let { (page, metadata) -> page?.invoke(); metadata() }
    }

    fun refresh() {
        val actions = synchronized(lock) {
            if (closed || mutable.value.refreshing) null else {
                mutable.value = mutable.value.copy(refreshing = true)
                val page = mutable.value.query?.let { feed.prepareSelection(it, fresh = true) }
                page to prepareCatalog(fresh = true)
            }
        }
        actions?.let { (page, metadata) -> page?.invoke(); metadata() }
    }

    fun selectWeek(id: String) = changeSelection(weekId = id)
    fun selectType(id: String) = changeSelection(typeId = id)

    private fun changeSelection(weekId: String? = null, typeId: String? = null) {
        val action = synchronized(lock) {
            val before = mutable.value
            val catalog = before.catalog
            when {
                closed || catalog == null -> null
                weekId != null && catalog.categories.none { it.id == weekId } -> null
                typeId != null && catalog.types.none { it.id == typeId } -> null
                else -> {
                    val next = before.copy(weekId = weekId ?: before.weekId, typeId = typeId ?: before.typeId)
                    mutable.value = next
                    if (next.query == before.query) null else next.query?.let { feed.prepareSelection(it, fresh = next.refreshing) }
                }
            }
        }
        action?.invoke()
        synchronized(lock) { finishRefreshIfIdle() }
    }

    fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            epoch++
            mutable.value = mutable.value.copy(catalogLoading = false, refreshing = false)
            val previous = catalogJob
            catalogJob = null
            previous to feed.prepareClose()
        }
        previous.first?.cancel()
        previous.second()
        monitor.cancel()
    }

    /** Called under lock; returns a start action instead of doing IO or launching inline. */
    private fun prepareCatalog(fresh: Boolean): () -> Unit {
        val ticket = ++epoch
        val previous = catalogJob
        mutable.value = mutable.value.copy(catalogLoading = true, catalogError = null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                withTimeoutOrNull(timeoutMillis) { catalogLoader(fresh) }
                    ?: JmxResult.Failure(JmxError.Network("期刊加载超时，请重试。"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                JmxResult.Failure(JmxError.Network("期刊加载失败，请重试。"))
            }
            currentCoroutineContext().ensureActive()
            val pageAction = synchronized(lock) {
                if (closed || epoch != ticket) null else {
                    val before = mutable.value
                    mutable.value = when (result) {
                        is JmxResult.Success -> before.reconcile(result.value).copy(catalogLoading = false, catalogError = null)
                        is JmxResult.Failure -> before.copy(catalogLoading = false, catalogError = result.error.toUiMessage())
                    }
                    val next = mutable.value.query
                    val action = when {
                        result !is JmxResult.Success -> null
                        next == null -> feed.prepareClear()
                        next != feed.state.value.query -> feed.prepareSelection(next, fresh = before.refreshing)
                        else -> null // Same winning query: NEVER launch a second forced first page.
                    }
                    finishRefreshIfIdle()
                    action
                }
            }
            pageAction?.invoke()
        }
        catalogJob = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (!closed && epoch == ticket) {
                    catalogJob = null
                    mutable.value = mutable.value.copy(catalogLoading = false)
                    finishRefreshIfIdle()
                }
            }
        }
        return { previous?.cancel(); job.start() }
    }

    private fun finishRefreshIfIdle() {
        if (mutable.value.refreshing && !mutable.value.catalogLoading && !feed.state.value.loading) {
            mutable.value = mutable.value.copy(refreshing = false)
        }
    }
}
