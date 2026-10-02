package app.prismia.source.iwara

data class IwaraVideoRecord(
    val id: String,
    val title: String,
    val username: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val tags: List<String> = emptyList(),
    val body: String? = null,
    val rating: String? = null,
    val status: String? = null,
)

data class IwaraPage(
    val items: List<IwaraVideoRecord>,
    val page: Int,
    val limit: Int,
) {
    val hasMore: Boolean get() = items.size >= limit
}

data class IwaraStream(
    val name: String,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
)
