package dev.jmx.client.core.api

import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.ApiRoute
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
        if (albumId.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("albumId is blank", field = "albumId"))
        }
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Album) {
                    query("id", albumId)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("album data is not an object"))
        return JmxResult.Success(root.toAlbumDetail())
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
        return data.toSearchPageOrNull()
            ?.let { JmxResult.Success(it) }
            ?: JmxResult.Failure(JmxError.Schema("search data is not object or array"))
    }
}
