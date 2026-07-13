package dev.jmx.client.core.api

import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class InteractionApi(
    private val apiClient: JmxApiClient
) {
    suspend fun likeAlbum(albumId: String): JmxResult<ActionResult> {
        if (albumId.isBlank()) return JmxResult.Failure(JmxError.Schema("albumId 为空", field = "albumId"))
        return action(
            ApiRoute.Like,
            form = {
                form("id", albumId)
            }
        )
    }

    suspend fun favoriteAlbum(albumId: String): JmxResult<ActionResult> {
        if (albumId.isBlank()) return JmxResult.Failure(JmxError.Schema("albumId 为空", field = "albumId"))
        return action(
            ApiRoute.FavoriteAction,
            form = {
                form("aid", albumId)
            }
        )
    }

    suspend fun albumComments(
        albumId: String,
        page: Int = 1,
        mode: String = "manhua"
    ): JmxResult<CommentPage> {
        if (albumId.isBlank()) return JmxResult.Failure(JmxError.Schema("albumId 为空", field = "albumId"))
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Forum) {
                    queryAtLeast("page", page, minimum = 1)
                    query("aid", albumId)
                    query("mode", mode)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("forum data 不是对象"))
        return JmxResult.Success(root.toCommentPage())
    }

    suspend fun commentAlbum(
        albumId: String,
        content: String,
        status: String,
        commentId: String? = null
    ): JmxResult<ActionResult> {
        if (albumId.isBlank()) return JmxResult.Failure(JmxError.Schema("albumId 为空", field = "albumId"))
        if (content.isBlank()) return JmxResult.Failure(JmxError.Schema("评论内容为空", field = "comment"))
        return action(
            ApiRoute.Comment,
            form = {
                form("comment", content)
                form("aid", albumId)
                form("status", status)
                form("comment_id", commentId)
            }
        )
    }

    private suspend fun action(
        route: ApiRoute,
        form: dev.jmx.client.core.network.ApiRequestBuilder.() -> Unit
    ): JmxResult<ActionResult> {
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(route) {
                    form()
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("${route.path} data 不是对象"))
        return JmxResult.Success(root.toActionResult())
    }
}
