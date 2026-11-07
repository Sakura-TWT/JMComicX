package dev.jmx.client

import dev.jmx.client.core.api.CategoryFilter
import dev.jmx.client.core.api.WeekInfo
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.publicRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only discovery, sharing the existing core, image health registry and album/detail contracts. */
internal class DiscoveryRepository(private val home: HomeRepository) {
    private val catalogLock = Any()
    private var catalogEpoch = 0L
    @Volatile private var rememberedCatalog: WeekInfo? = null
    /** Provisional UI snapshot, not a claim of freshness; activation still reconciles metadata. */
    val catalogSnapshot: WeekInfo? get() = rememberedCatalog

    suspend fun week(forceRefresh: Boolean): JmxResult<WeekInfo> = withContext(Dispatchers.IO) {
        val ticket = synchronized(catalogLock) { ++catalogEpoch }
        val result = home.core.publicRead { home.core.libraryApi.week(forceRefresh) }
        currentCoroutineContext().ensureActive()
        synchronized(catalogLock) {
            if (ticket == catalogEpoch && result is JmxResult.Success) rememberedCatalog = result.value
        }
        result
    }
    suspend fun page(query: DiscoveryQuery, page: Int, forceRefresh: Boolean): JmxResult<DiscoveryPage> = withContext(Dispatchers.IO) {
        val result = home.core.publicRead { when (query) {
            is DiscoveryQuery.Weekly -> home.core.libraryApi.weekFilter(page, query.weekId, query.typeId, forceRefresh)
            is DiscoveryQuery.Ranking -> home.core.libraryApi.categoriesFilter(
                CategoryFilter(page = page, time = query.period.value, category = query.categoryId, order = JmxMagicConstants.ORDER_BY_VIEW),
                forceRefresh = forceRefresh,
            )
        } }
        when (result) {
            is JmxResult.Failure -> result
            is JmxResult.Success -> JmxResult.Success(DiscoveryPage(result.value.content.map { it.toHomeAlbum(home.currentImageHost) }, result.value.total))
        }
    }
}
