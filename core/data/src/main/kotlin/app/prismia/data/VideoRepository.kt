package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.toSourceFailure
import app.prismia.video.PagedVideoCatalog
import app.prismia.video.VideoAvailability
import app.prismia.video.VideoPage
import app.prismia.video.VideoWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

data class VideoSourceFailure(
    val source: ContentSource,
    val error: Exception,
) {
    /** Stable diagnostic contract used by callers without depending on source exceptions. */
    val diagnostic: SourceFailure
        get() = error.toSourceFailure(source, "federated.video")
}

data class FederatedVideoPage(
    val items: List<VideoWork>,
    val page: Int,
    val hasMore: Boolean,
    val failures: List<VideoSourceFailure> = emptyList(),
) {
    fun asVideoPage(): VideoPage = VideoPage(items = items, page = page, hasMore = hasMore, failures = failures.map { it.diagnostic })
}

/**
 * First federated repository: fetches the two video sources independently and
 * merges only when the stable iwara ID is present. Source-specific metadata is
 * kept on the work instead of being silently overwritten.
 */
class FederatedVideoRepository(
    private val oreno: PagedVideoCatalog,
    private val iwara: PagedVideoCatalog,
) {
    suspend fun browseOreno(page: Int = 0, limit: Int = 32): VideoPage = oreno.browse(page, limit)

    suspend fun browseIwara(page: Int = 0, limit: Int = 32): VideoPage = iwara.browse(page, limit)

    suspend fun search(query: String, page: Int = 0, limit: Int = 32): VideoPage =
        searchDetailed(query, page, limit).asVideoPage()

    suspend fun searchDetailed(query: String, page: Int = 0, limit: Int = 32): FederatedVideoPage {
        require(page >= 0) { "page must be non-negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return supervisorScope {
            val orenoDeferred = async { fetch(ContentSource.ORENO3D) { oreno.searchPage(query, page, limit) } }
            val iwaraDeferred = async { fetch(ContentSource.IWARA) { iwara.searchPage(query, page, limit) } }
            val orenoResult = orenoDeferred.await()
            val iwaraResult = iwaraDeferred.await()
            val merged = merge(orenoResult.page?.items.orEmpty(), iwaraResult.page?.items.orEmpty())
            FederatedVideoPage(
                items = merged,
                page = page,
                hasMore = (orenoResult.page?.hasMore == true) || (iwaraResult.page?.hasMore == true),
                failures = listOfNotNull(orenoResult.failure, iwaraResult.failure),
            )
        }
    }

    private suspend fun fetch(
        source: ContentSource,
        block: suspend () -> VideoPage,
    ): SourcePageResult = try {
        SourcePageResult(page = block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        SourcePageResult(failure = VideoSourceFailure(source, failure))
    }

    private data class SourcePageResult(
        val page: VideoPage? = null,
        val failure: VideoSourceFailure? = null,
    )

    private fun merge(orenoItems: List<VideoWork>, iwaraItems: List<VideoWork>): List<VideoWork> {
        // One pass, one stable identity per emitted record, including duplicate
        // Oreno entries that link to the same Iwara video.
        val merged = linkedMapOf<ContentKey, VideoWork>()
        for (item in orenoItems.asSequence() + iwaraItems.asSequence()) {
            val identity = iwaraIdentity(item)?.let {
                ContentKey(app.prismia.foundation.ContentType.VIDEO, ContentSource.IWARA, it)
            } ?: item.key
            val previous = merged[identity]
            merged[identity] = if (previous == null) item else mergeVideoMetadata(previous, item).copy(
                availability = mergeAvailability(previous.availability, item.availability),
            )
        }
        return merged.values.toList()
    }

    private fun iwaraIdentity(work: VideoWork): String? =
        if (work.key.source == ContentSource.IWARA) work.key.remoteId
        else work.sourceLinks[ContentSource.IWARA]?.takeIf(String::isNotBlank)

    private fun mergeAvailability(
        first: VideoAvailability,
        second: VideoAvailability,
    ) = when {
        first == VideoAvailability.PLAYABLE || second == VideoAvailability.PLAYABLE -> VideoAvailability.PLAYABLE
        first == VideoAvailability.TOMBSTONED || second == VideoAvailability.TOMBSTONED -> VideoAvailability.TOMBSTONED
        first != VideoAvailability.UNKNOWN -> first
        else -> second
    }
}
