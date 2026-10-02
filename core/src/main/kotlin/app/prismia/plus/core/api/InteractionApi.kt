package app.prismia.plus.core.api

import app.prismia.plus.core.network.JmxApiClient
import app.prismia.plus.core.network.apiRequest
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult

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
        return when (val result = action(
            ApiRoute.FavoriteAction,
            form = { form("aid", albumId) },
        )) {
            is JmxResult.Failure -> result
            is JmxResult.Success -> result.value.requireFavoriteSuccess()
        }
    }

    /** 移动已有收藏；favorite 是 toggle，不能用作资料夹搬移。 */
    suspend fun moveFavoritesToFolder(albumIds: List<String>, folderId: Int): JmxResult<ActionResult> {
        if (folderId <= 0) return JmxResult.Failure(JmxError.Schema("请选择实际收藏资料夹", field = "folder_id"))
        if (albumIds.isEmpty() || albumIds.any { !it.matches(Regex("[0-9]+")) }) {
            return JmxResult.Failure(JmxError.Schema("漫画编号无效", field = "aid"))
        }
        return when (val result = action(ApiRoute.FavoriteFolderAction) {
            form("type", "move")
            form("folder_id", folderId.toString())
            form("aid", albumIds.distinct().joinToString(","))
        }) {
            is JmxResult.Failure -> result
            is JmxResult.Success -> result.value.requireFavoriteSuccess()
        }
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
        form: app.prismia.plus.core.network.ApiRequestBuilder.() -> Unit
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

internal fun ActionResult.requireFavoriteSuccess(): JmxResult<ActionResult> =
    if (status == "ok") JmxResult.Success(this)
    else JmxResult.Failure(JmxError.Schema(message ?: "收藏操作未得到服务端确认", field = "status"))
