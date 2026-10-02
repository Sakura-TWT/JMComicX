package app.prismia.foundation

/** The top-level experience currently shown by the app shell. */
enum class AppMode {
    COMIC,
    VIDEO,
}

enum class ContentType {
    COMIC,
    VIDEO,
}

enum class ContentSource {
    JM_COMIC,
    ORENO3D,
    IWARA,
}

/** Stable local identity. Remote IDs are never compared without their source. */
data class ContentKey(
    val contentType: ContentType,
    val source: ContentSource,
    val remoteId: String,
) {
    init {
        require(remoteId.isNotBlank()) { "remoteId must not be blank" }
    }
}

data class ContentRef(
    val key: ContentKey,
    val title: String,
    val coverUrl: String? = null,
)
