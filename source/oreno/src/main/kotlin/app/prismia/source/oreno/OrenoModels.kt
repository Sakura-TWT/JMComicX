package app.prismia.source.oreno

data class OrenoVideoRecord(
    val id: String,
    val title: String,
    val iwaraVideoId: String? = null,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val tags: List<String> = emptyList(),
    val viewCount: Long? = null,
    val likeCount: Long? = null,
)

data class OrenoPage(
    val items: List<OrenoVideoRecord>,
    val page: Int,
    val hasMore: Boolean,
)
