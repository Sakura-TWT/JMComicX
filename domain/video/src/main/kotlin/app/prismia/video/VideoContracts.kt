package app.prismia.video

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentRef
import app.prismia.foundation.ContentType

data class VideoWork(
    val key: ContentKey,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val availability: VideoAvailability = VideoAvailability.UNKNOWN,
    val sourceLinks: Map<ContentSource, String> = emptyMap(),
) {
    init {
        require(key.contentType == ContentType.VIDEO) { "VideoWork requires a VIDEO content key" }
    }
}

enum class VideoAvailability {
    UNKNOWN,
    PLAYABLE,
    TOMBSTONED,
    NO_SOURCE,
}

data class StreamVariant(
    val name: String,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
) {
    init {
        require(url.isNotBlank()) { "stream URL must not be blank" }
    }
}

data class VideoDetail(
    val work: VideoWork,
    val variants: List<StreamVariant> = emptyList(),
    val related: List<VideoWork> = emptyList(),
)

data class VideoPage(
    val items: List<VideoWork>,
    val page: Int,
    val hasMore: Boolean,
) {
    init {
        require(page >= 0) { "page must be non-negative" }
    }
}

interface VideoCatalog {
    suspend fun find(query: String): List<VideoWork>
    suspend fun detail(key: ContentKey): VideoWork
}

interface PagedVideoCatalog : VideoCatalog {
    suspend fun browse(page: Int = 0, limit: Int = 32): VideoPage
    suspend fun searchPage(query: String, page: Int = 0, limit: Int = 32): VideoPage
    suspend fun detailPage(key: ContentKey): VideoDetail
}

fun VideoWork.asRef(): ContentRef = ContentRef(
    key = key,
    title = title,
    coverUrl = coverUrl,
)
