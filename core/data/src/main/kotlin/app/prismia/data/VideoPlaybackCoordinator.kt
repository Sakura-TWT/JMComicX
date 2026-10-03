package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.video.PagedVideoCatalog
import app.prismia.video.StreamVariant
import app.prismia.video.isUsable

data class ResolvedVideoStream(
    val variant: StreamVariant,
    val refreshed: Boolean,
)

/** Refreshes source signed URLs only when the cached URL is expired or unsafe. */
class VideoPlaybackCoordinator(
    private val catalog: PagedVideoCatalog,
    private val nowEpochSeconds: () -> Long,
    private val expirySafetyWindowSeconds: Long = 15,
) {
    init {
        require(expirySafetyWindowSeconds >= 0) { "expiry safety window must be non-negative" }
    }

    suspend fun resolve(
        key: ContentKey,
        cachedVariant: StreamVariant? = null,
        preferredName: String? = null,
    ): ResolvedVideoStream {
        if (cachedVariant?.isUsable(nowEpochSeconds(), expirySafetyWindowSeconds) == true) {
            return ResolvedVideoStream(cachedVariant, refreshed = false)
        }
        val detail = catalog.detailPage(key)
        val variant = detail.variants.firstOrNull { it.name == preferredName }
            ?: detail.variants.firstOrNull()
            ?: throw VideoPlaybackUnavailableException(key)
        return ResolvedVideoStream(variant, refreshed = true)
    }
}

class VideoPlaybackUnavailableException(key: ContentKey) : IllegalStateException(
    "no playable stream for ${key.source}:${key.remoteId}",
)
