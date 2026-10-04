package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.foundation.toSourceFailure
import app.prismia.video.VideoAvailability
import app.prismia.video.VideoDetail
import app.prismia.video.VideoDetailProvider
import app.prismia.video.VideoWork
import kotlinx.coroutines.CancellationException

/**
 * Joins metadata discovery to the authoritative playback source. Depends only
 * on domain contracts and does not persist expiring media URLs or credentials.
 */
class VideoDetailRepository(
    private val oreno: VideoDetailProvider,
    private val iwara: VideoDetailProvider,
) : VideoDetailProvider {
    override suspend fun detailPage(key: ContentKey): VideoDetail {
        require(key.contentType == ContentType.VIDEO)
        if (key.source == ContentSource.IWARA) return iwara.detailPage(key)
        require(key.source == ContentSource.ORENO3D) { "unsupported video source" }

        val indexed = oreno.detailPage(key)
        val id = indexed.work.sourceLinks[ContentSource.IWARA]?.takeIf(String::isNotBlank)
            ?: return indexed.copy(work = indexed.work.copy(availability = VideoAvailability.NO_SOURCE), variants = emptyList())
        val playable = try {
            iwara.detailPage(ContentKey(ContentType.VIDEO, ContentSource.IWARA, id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val diagnostic = failure.toSourceFailure(ContentSource.IWARA, "detail.playback")
            return indexed.copy(
                work = indexed.work.copy(availability = if (diagnostic.httpStatus in listOf(404, 410)) {
                    VideoAvailability.TOMBSTONED
                } else VideoAvailability.UNKNOWN),
                variants = emptyList(),
                failures = indexed.failures + diagnostic,
            )
        }
        return VideoDetail(
            work = mergeVideoMetadata(indexed.work, playable.work).copy(availability = playable.work.availability),
            variants = playable.variants,
            related = indexed.related.ifEmpty { playable.related },
            failures = indexed.failures + playable.failures,
        )
    }
}

/** Ordered metadata preference; stream availability is decided by the caller. */
internal fun mergeVideoMetadata(primary: VideoWork, secondary: VideoWork): VideoWork = primary.copy(
    title = primary.title.ifBlank { secondary.title },
    author = primary.author?.takeIf(String::isNotBlank) ?: secondary.author,
    coverUrl = primary.coverUrl?.takeIf(String::isNotBlank) ?: secondary.coverUrl,
    durationMs = primary.durationMs ?: secondary.durationMs,
    description = primary.description?.takeIf(String::isNotBlank) ?: secondary.description,
    tags = (primary.tags + secondary.tags).distinct(),
    sourceLinks = secondary.sourceLinks + primary.sourceLinks,
)
