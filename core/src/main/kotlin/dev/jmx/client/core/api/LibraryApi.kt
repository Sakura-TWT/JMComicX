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

    suspend fun week(forceRefresh: Boolean = false): JmxResult<WeekInfo> {
        val data = when (val result = apiClient.requestJson(apiRequest(ApiRoute.Week) { this.forceRefresh(forceRefresh) })) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("/week data is not an object"))
        return root.discoveryWeekInfo()
    }

    suspend fun weekFilter(page: Int, categoryId: String, typeId: String, forceRefresh: Boolean = false): JmxResult<AlbumPage> {
        if (categoryId.isBlank() || typeId.isBlank()) return JmxResult.Failure(JmxError.Schema("每周必看缺少筛选项", field = "id/type"))
        return albumPage(ApiRoute.WeekFilter, forceRefresh = forceRefresh) {
            queryAtLeast("page", page, minimum = 1)
            query("id", categoryId)
            query("type", typeId)
        }
    }

    suspend fun categoriesFilter(filter: CategoryFilter, forceRefresh: Boolean = false): JmxResult<AlbumPage> {
        return albumPage(ApiRoute.CategoriesFilter, forceRefresh = forceRefresh) {
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
        val data = when (val result = apiClient.requestJson(apiRequest(ApiRoute.WatchList) {
            queryAtLeast("page", page, minimum = 1)
        })) {
            is JmxResult.Failure -> return result
            is JmxResult.Success -> result.value
        }
        val list = when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> listOf("content", "list", "data", "albums", "photos")
                .firstNotNullOfOrNull { data.asJsonObject.get(it)?.takeUnless { value -> value.isJsonNull } }
                ?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return JmxResult.Failure(JmxError.Schema("观看记录列表缺失，不能确认记录为空", field = "content"))
        if (list.any { !it.isJsonObject }) return JmxResult.Failure(JmxError.Schema("观看记录列表含不明条目", field = "content"))
        val albums = list.map { it.asJsonObject.toAlbumSummary() }
        if (albums.any { it.id.isBlank() }) return JmxResult.Failure(JmxError.Schema("观看记录缺少漫画编号", field = "id"))
        val total = if (data.isJsonObject) data.asJsonObject.intOrNull("total") else null
        if (total != null && total < albums.size) return JmxResult.Failure(JmxError.Schema("观看记录总数不一致", field = "total"))
        return JmxResult.Success(AlbumPage(total, albums, data.asObjectOrNull()?.toRawMap() ?: mapOf("content" to data.toString())))
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
                if (action?.acceptsHistoryRemoval() == true) {
                    JmxResult.Success(Unit)
                } else {
                    JmxResult.Failure(
                        if (action?.status.isNullOrBlank()) JmxError.EmptyData("观看记录删除尚未确认，需要重新查询")
                        else JmxError.Schema(action.message ?: "观看记录未删除", field = "status")
                    )
                }
            }
            is JmxResult.Failure -> when {
                result.error is JmxError.Schema && result.error.field == "data" -> JmxResult.Failure(JmxError.EmptyData("观看记录删除返回不明载荷，需要重新查询"))
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

    /** Empty data is ambiguous until the account layer has verified a fresh session. */
    suspend fun dailyInfo(userId: String): JmxResult<DailyCheckInfo?> {
        if (userId.isBlank()) return JmxResult.Failure(JmxError.Schema("userId is blank", field = "userId"))
        return when (val result = apiClient.requestJson(apiRequest(ApiRoute.Daily) { query("user_id", userId) })) {
            is JmxResult.Failure -> result
            is JmxResult.Success -> {
                val root = result.value.asObjectOrNull()
                    ?: return JmxResult.Failure(JmxError.Schema("daily data is not an object"))
                val info = root.toDailyCheckInfo()
                if (info.dailyId == null && info.records.isEmpty()) JmxResult.Failure(JmxError.EmptyData("签到活动信息缺失，需要确认会话"))
                else JmxResult.Success(info)
            }
        }
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
        forceRefresh: Boolean = false,
        build: ApiRequestBuilder.() -> Unit
    ): JmxResult<AlbumPage> {
        val data = when (val result = apiClient.requestJson(apiRequest(route, build).copy(forceRefresh = forceRefresh))) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        if (route == ApiRoute.WeekFilter || route == ApiRoute.CategoriesFilter) return data.discoveryPage()
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

/** 缺失状态需要读回验证，只有明确状态才算写请求已确认。 */
internal fun ActionResult.acceptsHistoryRemoval(): Boolean {
    val normalized = status?.trim()?.lowercase()
    return normalized in HISTORY_REMOVAL_SUCCESS
}

/** 只接受纯数字漫画编号，避免把清空哨兵或其他字段误当成单条删除。 */
internal fun watchHistoryDeleteIdOrNull(albumId: String): String? =
    albumId.trim().takeIf { it.matches(WATCH_HISTORY_ID) }

private val HISTORY_REMOVAL_SUCCESS = setOf("ok", "success", "1", "true")
private val WATCH_HISTORY_ID = Regex("[0-9]+")
