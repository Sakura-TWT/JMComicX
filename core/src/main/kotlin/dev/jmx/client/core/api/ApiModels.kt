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
    val profile: UserProfile?,
    val raw: Map<String, Any?>
)

data class UserProfile(
    val id: Int?,
    val username: String?,
    val email: String?,
    val avatar: String?,
    val level: Int?,
    val levelName: String?,
    val currentLevelExp: Int?,
    val nextLevelExp: Int?,
    val expPercent: Double?,
    val currentFavoriteCount: Int?,
    val maxFavoriteCount: Int?,
    val coin: Int?,
    val raw: Map<String, Any?>
)

data class AlbumSummary(
    val id: String,
    val name: String?,
    val author: String?,
    val imageCount: Int?
)

data class AlbumDetail(
    val id: String,
    val name: String?,
    val description: String?,
    val authors: List<String>,
    val imageCount: Int?,
    val totalViews: Int?,
    val likes: Int?,
    val commentTotal: Int?,
    val tags: List<String>,
    val actors: List<String>,
    val works: List<String>,
    val isFavorite: Boolean?,
    val liked: Boolean?,
    val related: List<RelatedAlbum>,
    val series: List<AlbumChapter>,
    val seriesId: String?,
    val price: Int?,
    val purchased: Boolean?,
    val raw: Map<String, Any?>
) {
    val summary: AlbumSummary = AlbumSummary(
        id = id,
        name = name,
        author = authors.firstOrNull(),
        imageCount = imageCount
    )
}

data class RelatedAlbum(
    val id: String,
    val name: String?,
    val author: String?,
    val image: String?
)

data class AlbumChapter(
    val id: String,
    val name: String?,
    val sort: String?
)

data class PhotoDetail(
    val id: String,
    val name: String?,
    val seriesId: String?,
    val sort: Int?,
    val tags: List<String>,
    val authors: List<String>,
    val scrambleId: Int?,
    val pageArr: List<String>,
    val imageDomain: String?,
    val imageCount: Int?,
    val raw: Map<String, Any?>
) {
    val isSingleAlbum: Boolean = seriesId == null || seriesId == "0" || seriesId == id

    val albumId: String = if (isSingleAlbum) id else (seriesId ?: id)

    fun imageUrls(cacheQuery: String? = null): List<String> {
        val host = imageDomain ?: return emptyList()
        return pageArr.map { fileName ->
            dev.jmx.client.core.image.ImageUrl.ofFileName(
                imageHost = host,
                photoId = id,
                fileName = fileName,
                cacheQuery = cacheQuery
            )
        }
    }
}

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

data class FavoriteFolder(
    val id: String,
    val name: String?,
    val ownerUserId: String? = null
)

data class FavoritePage(
    val total: Int?,
    val content: List<AlbumSummary>,
    val folders: List<FavoriteFolder>,
    val raw: Map<String, Any?>
) {
    val page: AlbumPage = AlbumPage(total = total, content = content, raw = raw)
}

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
    val order: String = dev.jmx.client.core.protocol.JmxMagicConstants.ORDER_BY_LATEST,
    val mainTag: Int = 0
) {

    val mobileOrderParam: String =
        dev.jmx.client.core.protocol.JmxMagicConstants.categoriesFilterOrder(order, time)
}

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
