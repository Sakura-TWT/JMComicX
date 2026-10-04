package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import app.prismia.video.VideoDetailProvider
import app.prismia.video.VideoAvailability
import app.prismia.video.StreamVariant
import app.prismia.video.isUsable

data class ResolvedVideoStream(
    val variant: StreamVariant,
    val refreshed: Boolean,
)

/** Resolves through a domain provider, including cross-source detail joins. */
class VideoPlaybackCoordinator(
    private val catalog: VideoDetailProvider,
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
        if (cachedVariant?.expiresAtEpochSeconds != null &&
            cachedVariant.isUsable(nowEpochSeconds(), expirySafetyWindowSeconds) &&
            (preferredName == null || cachedVariant.name.equals(preferredName, ignoreCase = true))
        ) {
            return ResolvedVideoStream(cachedVariant, refreshed = false)
        }
        val detail = catalog.detailPage(key)
        if (detail.work.availability == VideoAvailability.TOMBSTONED || detail.work.availability == VideoAvailability.NO_SOURCE) {
            throw VideoPlaybackUnavailableException(key, detail.failures.firstOrNull())
        }
        // Read the clock after the request: network latency consumes URL lifetime.
        val now = nowEpochSeconds()
        val candidates = detail.variants.filter { it.isUsable(now, expirySafetyWindowSeconds) }
        val variant = candidates.firstOrNull { it.name.equals(preferredName, ignoreCase = true) }
            ?: candidates.maxWithOrNull(QUALITY_ORDER)
            ?: throw VideoPlaybackUnavailableException(key, detail.failures.firstOrNull())
        return ResolvedVideoStream(variant, refreshed = true)
    }

    private companion object {
        val QUALITY_ORDER = compareBy<StreamVariant> {
            when (it.name.lowercase()) {
                "source", "original" -> Int.MAX_VALUE
                "preview" -> -1
                else -> it.height ?: it.name.lowercase().removeSuffix("p").toIntOrNull() ?: 0
            }
        }.thenBy { it.bitrate ?: 0 }.thenBy { it.width ?: 0 }.thenBy { it.name }
    }
}

class VideoPlaybackUnavailableException(
    key: ContentKey,
    upstream: SourceFailure? = null,
) : IllegalStateException("no usable stream for " + key.source + ":" + key.remoteId, upstream?.cause), SourceFailureCarrier {
    override val sourceFailure = SourceFailure(
        source = key.source,
        operation = "playback.resolve",
        category = SourceErrorCategory.PLAYBACK,
        httpStatus = upstream?.httpStatus,
        retryable = upstream?.retryable ?: false,
        message = "no usable stream is available",
        cause = upstream?.cause,
    )
}
