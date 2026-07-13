package dev.jmx.client.core.api

data class RemoteSetting(
    val apiVersion: String?,
    val imageHost: String?,
    val shunts: List<ApiShunt>
)

data class ApiShunt(
    val id: String,
    val name: String
)

data class LoginSession(
    val avs: String?,
    val raw: Map<String, Any?>
)

data class AlbumSummary(
    val id: String,
    val name: String?,
    val author: String?,
    val imageCount: Int?
)

data class SearchPage(
    val total: Int?,
    val redirectAlbumId: String?,
    val content: List<AlbumSummary>
)

data class AlbumPage(
    val total: Int?,
    val content: List<AlbumSummary>,
    val raw: Map<String, Any?>
)

data class WeekInfo(
    val categories: List<WeekCategory>,
    val types: List<WeekType>,
    val raw: Map<String, Any?>
)

data class WeekCategory(
    val id: String,
    val time: String?,
    val title: String?
)

data class WeekType(
    val id: String,
    val title: String?
)

data class CategoryFilter(
    val page: Int,
    val time: String,
    val category: String,
    val order: String = "mr",
    val mainTag: Int = 0
)

data class ActionResult(
    val status: String?,
    val message: String?,
    val type: String?,
    val raw: Map<String, Any?>
)

data class CommentItem(
    val id: String?,
    val userId: String?,
    val username: String?,
    val content: String?,
    val createdAt: String?,
    val likes: Int?,
    val replies: List<CommentItem>
)

data class CommentPage(
    val total: Int?,
    val comments: List<CommentItem>,
    val raw: Map<String, Any?>
)

data class DailyCheckInfo(
    val dailyId: Int?,
    val eventName: String?,
    val currentProgress: String?,
    val records: List<DailyRecord>,
    val raw: Map<String, Any?>
)

data class DailyRecord(
    val date: String?,
    val signed: Boolean?,
    val bonus: Boolean?
)
