package dev.jmx.client.core.api

import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.protocol.JmId
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class AlbumApi(
    private val apiClient: JmxApiClient
) {
    suspend fun detail(albumId: String): JmxResult<AlbumSummary> {
        return when (val result = detailFull(albumId)) {
            is JmxResult.Success -> JmxResult.Success(result.value.summary)
            is JmxResult.Failure -> result
        }
    }

    suspend fun detailFull(albumId: String): JmxResult<AlbumDetail> {
        val id = when (val parsed = JmId.parse(albumId)) {
            is JmxResult.Success -> parsed.value
            is JmxResult.Failure -> return parsed
        }
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Album) {
                    query("id", id)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("album data is not an object"))
        if (root.stringOrNull("name", "title") == null) {
            return JmxResult.Failure(
                JmxError.Api(code = 404, message = "本子不存在或无数据：$id")
            )
        }
        return JmxResult.Success(root.toAlbumDetail())
    }

    suspend fun search(
        query: String,
        page: Int,
        order: String = JmxMagicConstants.ORDER_BY_LATEST,
        mainTag: Int = 0,
        time: String = JmxMagicConstants.TIME_ALL
    ): JmxResult<SearchPage> {
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Search) {
                    query("search_query", query)
                    queryAtLeast("page", page, minimum = 1)
                    query("o", order)
                    query("main_tag", mainTag)
                    query("t", time)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val page = data.toSearchPageOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("search data is not object or array"))

        return JmxResult.Success(page)
    }

    suspend fun searchResolved(
        query: String,
        page: Int,
        order: String = JmxMagicConstants.ORDER_BY_LATEST,
        mainTag: Int = 0,
        time: String = JmxMagicConstants.TIME_ALL
    ): JmxResult<SearchPage> {
        val searchPage = when (val result = search(query, page, order, mainTag, time)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val redirectId = searchPage.redirectAlbumId?.takeIf { it.isNotBlank() }
            ?: return JmxResult.Success(searchPage)
        return when (val detail = detailFull(redirectId)) {
            is JmxResult.Success -> JmxResult.Success(
                SearchPage(
                    total = 1,
                    redirectAlbumId = redirectId,
                    content = listOf(detail.value.summary)
                )
            )
            is JmxResult.Failure -> detail
        }
    }
}
