package dev.jmx.client.core.api

import dev.jmx.client.core.network.ApiRequestBuilder
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class LibraryApi(
    private val apiClient: JmxApiClient
) {
    suspend fun promotedSections(timestampMillis: Long = System.currentTimeMillis()): JmxResult<List<HomePromoteSection>> {
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Promote) {
                    query("_", timestampMillis)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val sections = when {
            data.isJsonArray -> data.asObjectListOrEmpty().map { it.toHomePromoteSection() }
            data.isJsonObject -> listOf(data.asJsonObject.toHomePromoteSection())
            else -> emptyList()
        }
        return JmxResult.Success(sections)
    }

    suspend fun promotedAlbums(timestampMillis: Long = System.currentTimeMillis()): JmxResult<List<AlbumSummary>> {
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Promote) {
                    query("_", timestampMillis)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val albums = when {
            data.isJsonArray -> data.asObjectListOrEmpty().map { it.toAlbumSummary() }
            data.isJsonObject -> data.asJsonObject.firstAlbumList()
            else -> emptyList()
        }
        return JmxResult.Success(albums)
    }

    suspend fun promotedSectionPage(
        section: HomePromoteSection,
        page: Int,
    ): JmxResult<AlbumPage> {
        return when (section.type?.trim()?.lowercase()) {
            "promote" -> {
                val id = section.id.ifBlank { section.filterValue.orEmpty() }
                if (id.isBlank()) {
                    JmxResult.Failure(JmxError.Schema("推荐分组缺少 id", field = "id"))
                } else {
                    albumPage(ApiRoute.PromoteList) {
                        query("id", id)
                        queryAtLeast("page", page, minimum = 1)
                    }
                }
            }
            "category_id" -> {
                val category = section.slug?.trim().orEmpty()
                if (category.isBlank()) {
                    JmxResult.Failure(JmxError.Schema("分类分组缺少 slug", field = "slug"))
                } else {
                    categoriesFilter(
                        CategoryFilter(
                            page = page,
                            time = dev.jmx.client.core.protocol.JmxMagicConstants.TIME_ALL,
                            category = category,
                        )
                    )
                }
            }
            "not_in_category_id" -> {
                val queryText = section.slug?.trim().takeUnless { it.isNullOrBlank() }
                    ?: section.title?.trim().orEmpty()
                if (queryText.isBlank()) {
                    JmxResult.Failure(JmxError.Schema("搜索分组缺少标题", field = "title"))
                } else {
                    albumPage(ApiRoute.Search) {
                        query("search_query", queryText)
                        queryAtLeast("page", page, minimum = 1)
                        query("o", dev.jmx.client.core.protocol.JmxMagicConstants.ORDER_BY_LATEST)
                        query("main_tag", 0)
                        query("t", dev.jmx.client.core.protocol.JmxMagicConstants.TIME_ALL)
                    }
                }
            }
            else -> JmxResult.Failure(
                JmxError.Schema(
                    "推荐分组类型不支持漫画分页：${section.type.orEmpty()}",
                    field = "type",
                )
            )
        }
    }

    suspend fun week(): JmxResult<WeekInfo> {
        val data = when (val result = apiClient.requestJson(apiRequest(ApiRoute.Week))) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("/week data is not an object"))
        return JmxResult.Success(root.toWeekInfo())
    }

    suspend fun weekFilter(page: Int, categoryId: String, typeId: String): JmxResult<AlbumPage> {
        return albumPage(ApiRoute.WeekFilter) {
            queryAtLeast("page", page, minimum = 1)
            query("id", categoryId)
            query("type", typeId)
        }
    }

    suspend fun categoriesFilter(filter: CategoryFilter): JmxResult<AlbumPage> {

        return albumPage(ApiRoute.CategoriesFilter) {
            queryAtLeast("page", filter.page, minimum = 1)
            query("order", "")
            query("c", filter.category)
            query("o", filter.mobileOrderParam)
        }
    }

    suspend fun favoriteAlbums(
        page: Int,
        order: String,
        folderId: Int = 0
    ): JmxResult<AlbumPage> {
        return when (val result = favoritePage(page, order, folderId)) {
            is JmxResult.Success -> JmxResult.Success(result.value.page)
            is JmxResult.Failure -> result
        }
    }

    suspend fun favoritePage(
        page: Int,
        order: String,
        folderId: Int = 0
    ): JmxResult<FavoritePage> {
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Favorite) {
                    queryAtLeast("page", page, minimum = 1)
                    query("o", order)
                    query("folder_id", folderId)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("favorite data is not an object"))
        return JmxResult.Success(root.toFavoritePage())
    }

    suspend fun watchList(page: Int): JmxResult<AlbumPage> {
        return albumPage(ApiRoute.WatchList) {
            queryAtLeast("page", page, minimum = 1)
        }
    }

    /** 删除一条观看记录。移动端 POST `/watch_list`，表单 `id` 为漫画编号。 */
    suspend fun deleteWatchHistory(albumId: String): JmxResult<Unit> {
        val id = watchHistoryDeleteIdOrNull(albumId)
            ?: return JmxResult.Failure(JmxError.Schema("漫画编号无效", field = "id"))
        return postWatchHistoryRemoval(id)
    }

    private suspend fun postWatchHistoryRemoval(id: String): JmxResult<Unit> {
        return when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.WatchListAction) {
                    form("id", id)
                }
            )
        ) {
            is JmxResult.Success -> {
                val action = result.value.asObjectOrNull()?.toActionResult()
                if (action == null || action.acceptsHistoryRemoval()) {
                    JmxResult.Success(Unit)
                } else {
                    JmxResult.Failure(
                        JmxError.Schema(action.message ?: "观看记录未删除", field = "status")
                    )
                }
            }
            is JmxResult.Failure -> when {
                result.error is JmxError.Schema && result.error.field == "data" -> JmxResult.Success(Unit)
                else -> result
            }
        }
    }

    suspend fun userComments(page: Int, userId: String): JmxResult<CommentPage> {
        if (userId.isBlank()) return JmxResult.Failure(JmxError.Schema("userId is blank", field = "userId"))
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Forum) {
                    queryAtLeast("page", page, minimum = 1)
                    query("uid", userId)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("user forum data is not an object"))
        return JmxResult.Success(root.toCommentPage())
    }

    /**
     * 查询签到活动。Success(null) 表示服务端确认没有进行中的签到活动（空载荷）：
     * 首次遇到空载荷会换一条线路交叉验证，两条线路都为空才认定无活动，
     * 之后同一进程内不再重复交叉验证。
     */
    suspend fun dailyInfo(userId: String): JmxResult<DailyCheckInfo?> {
        if (userId.isBlank()) return JmxResult.Failure(JmxError.Schema("userId is blank", field = "userId"))
        return when (val outcome = requestDailyData(userId, excludedEndpointUrl = null)) {
            is DailyDataOutcome.Data -> JmxResult.Success(outcome.root.toDailyCheckInfo())
            is DailyDataOutcome.Empty -> resolveEmptyDailyPayload(userId, outcome.usedEndpointUrl)
            is DailyDataOutcome.Error -> JmxResult.Failure(outcome.error)
        }
    }

    @Volatile
    private var dailyEmptyPayloadConfirmedAcrossEndpoints = false

    private suspend fun resolveEmptyDailyPayload(
        userId: String,
        usedEndpointUrl: String?
    ): JmxResult<DailyCheckInfo?> {
        if (usedEndpointUrl == null || dailyEmptyPayloadConfirmedAcrossEndpoints) {
            return JmxResult.Success(null)
        }
        return when (val retry = requestDailyData(userId, excludedEndpointUrl = usedEndpointUrl)) {
            is DailyDataOutcome.Data -> JmxResult.Success(retry.root.toDailyCheckInfo())
            is DailyDataOutcome.Empty -> {
                dailyEmptyPayloadConfirmedAcrossEndpoints = true
                JmxResult.Success(null)
            }
            is DailyDataOutcome.Error -> JmxResult.Failure(retry.error)
        }
    }

    private suspend fun requestDailyData(userId: String, excludedEndpointUrl: String?): DailyDataOutcome {
        return when (
            val result = apiClient.requestJsonResponse(
                apiRequest(ApiRoute.Daily) {
                    query("user_id", userId)
                    excludeEndpointUrl(excludedEndpointUrl)
                }
            )
        ) {
            is JmxResult.Failure -> when (result.error) {
                is JmxError.EmptyData -> DailyDataOutcome.Empty(
                    usedEndpointUrl = result.error.exchange?.requestUrl
                )
                else -> DailyDataOutcome.Error(result.error)
            }
            is JmxResult.Success -> {
                val element = result.value.data
                when {
                    element.isJsonObject -> DailyDataOutcome.Data(element.asJsonObject)
                    element.isJsonNull -> DailyDataOutcome.Empty(
                        usedEndpointUrl = result.value.exchange.requestUrl
                    )
                    else -> DailyDataOutcome.Error(JmxError.Schema("daily data is not an object"))
                }
            }
        }
    }

    private sealed interface DailyDataOutcome {
        data class Data(val root: com.google.gson.JsonObject) : DailyDataOutcome
        data class Empty(val usedEndpointUrl: String?) : DailyDataOutcome
        data class Error(val error: JmxError) : DailyDataOutcome
    }

    suspend fun dailyCheck(userId: String, dailyId: String): JmxResult<ActionResult> {
        if (userId.isBlank()) return JmxResult.Failure(JmxError.Schema("userId is blank", field = "userId"))
        if (dailyId.isBlank()) return JmxResult.Failure(JmxError.Schema("dailyId is blank", field = "dailyId"))
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.DailyCheck) {
                    form("user_id", userId)
                    form("daily_id", dailyId)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("daily check data is not an object"))
        return JmxResult.Success(root.toActionResult())
    }

    private suspend fun albumPage(
        route: ApiRoute,
        build: ApiRequestBuilder.() -> Unit
    ): JmxResult<AlbumPage> {
        val data = when (val result = apiClient.requestJson(apiRequest(route, build))) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        return data.toAlbumPageOrNull()
            ?.let { JmxResult.Success(it) }
            ?: JmxResult.Failure(JmxError.Schema("${route.path} data is not object or array"))
    }

    private suspend fun rawObject(route: ApiRoute): JmxResult<Map<String, Any?>> {
        val data = when (val result = apiClient.requestJson(apiRequest(route))) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("${route.path} data is not an object"))
        return JmxResult.Success(root.toRawMap())
    }

    private fun com.google.gson.JsonObject.firstAlbumList(): List<AlbumSummary> {
        val page = toAlbumPage()
        if (page.content.isNotEmpty()) return page.content
        return entrySet()
            .firstNotNullOfOrNull { (_, value) ->
                value.asObjectListOrEmpty().takeIf { it.isNotEmpty() }?.map { it.toAlbumSummary() }
            }
            ?: emptyList()
    }
}

/** 观看记录删除接口经常不带 status；带了则只接受明确成功。 */
internal fun ActionResult.acceptsHistoryRemoval(): Boolean {
    val normalized = status?.trim()?.lowercase()
    return normalized.isNullOrEmpty() || normalized in HISTORY_REMOVAL_SUCCESS
}

/** 只接受纯数字漫画编号，避免把清空哨兵或其他字段误当成单条删除。 */
internal fun watchHistoryDeleteIdOrNull(albumId: String): String? =
    albumId.trim().takeIf { it.matches(WATCH_HISTORY_ID) }

private val HISTORY_REMOVAL_SUCCESS = setOf("ok", "success", "1", "true")
private val WATCH_HISTORY_ID = Regex("[0-9]+")
