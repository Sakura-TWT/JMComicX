package dev.jmx.client.core.api

import dev.jmx.client.core.network.ApiRequest
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class AlbumApi(
    private val apiClient: JmxApiClient
) {
    suspend fun detail(albumId: String): JmxResult<AlbumSummary> {
        if (albumId.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("albumId 为空", field = "albumId"))
        }
        val data = when (
            val result = apiClient.requestJson(
                ApiRequest(
                    route = ApiRoute.Album,
                    query = mapOf("id" to albumId)
                )
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("album data 不是对象"))
        return JmxResult.Success(root.toAlbumSummary())
    }

    suspend fun search(
        query: String,
        page: Int,
        order: String = "mr",
        mainTag: Int = 0,
        time: String = "a"
    ): JmxResult<SearchPage> {
        val data = when (
            val result = apiClient.requestJson(
                ApiRequest(
                    route = ApiRoute.Search,
                    query = mapOf(
                        "search_query" to query,
                        "page" to page.coerceAtLeast(1).toString(),
                        "o" to order,
                        "main_tag" to mainTag.toString(),
                        "t" to time
                    )
                )
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("search data 不是对象"))
        val content = root["content"].asObjectListOrEmpty().map { it.toAlbumSummary() }
        return JmxResult.Success(
            SearchPage(
                total = root.intOrNull("total"),
                redirectAlbumId = root.stringOrNull("redirect_aid", "redirectAid"),
                content = content
            )
        )
    }

    private fun com.google.gson.JsonObject.toAlbumSummary(): AlbumSummary {
        return AlbumSummary(
            id = stringOrNull("id", "album_id", "aid") ?: "",
            name = stringOrNull("name", "title"),
            author = stringOrNull("author"),
            imageCount = intOrNull("total_photo", "page_count", "images")
        )
    }
}
