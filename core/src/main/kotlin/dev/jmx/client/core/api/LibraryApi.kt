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

    suspend fun weekRaw(): JmxResult<Map<String, Any?>> {
        return rawObject(ApiRoute.Week)
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

    suspend fun dailyInfo(userId: String): JmxResult<DailyCheckInfo> {
        if (userId.isBlank()) return JmxResult.Failure(JmxError.Schema("userId is blank", field = "userId"))
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Daily) {
                    query("user_id", userId)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("daily data is not an object"))
        return JmxResult.Success(root.toDailyCheckInfo())
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
