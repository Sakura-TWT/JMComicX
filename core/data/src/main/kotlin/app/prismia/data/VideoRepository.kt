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
    fun asVideoPage(): VideoPage = VideoPage(items = items, page = page, hasMore = hasMore)
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
        require(limit > 0) { "limit must be positive" }
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
        val iwaraById = linkedMapOf<String, VideoWork>()
        iwaraItems.forEach { item ->
            iwaraIdentity(item)?.let { iwaraById.putIfAbsent(it, item) }
        }
        val consumed = mutableSetOf<String>()
        val emittedKeys = mutableSetOf<ContentKey>()
        val result = mutableListOf<VideoWork>()
        for (oreno in orenoItems) {
            val iwaraId = iwaraIdentity(oreno)
            val match = iwaraId?.let(iwaraById::get)
            val merged = if (match == null) {
                oreno
            } else {
                consumed += iwaraId
                mergeWork(oreno, match)
            }
            if (emittedKeys.add(merged.key)) result += merged
        }
        iwaraItems.forEach { iwaraItem ->
            val id = iwaraIdentity(iwaraItem)
            if (id == null || id !in consumed) {
                if (emittedKeys.add(iwaraItem.key)) result += iwaraItem
            }
        }
        return result
    }

    private fun iwaraIdentity(work: VideoWork): String? =
        work.sourceLinks[ContentSource.IWARA]?.takeIf(String::isNotBlank)
            ?: work.key.takeIf { it.source == ContentSource.IWARA }?.remoteId

    private fun mergeWork(primary: VideoWork, secondary: VideoWork): VideoWork = primary.copy(
        title = primary.title.ifBlank { secondary.title },
        author = primary.author ?: secondary.author,
        coverUrl = primary.coverUrl ?: secondary.coverUrl,
        durationMs = primary.durationMs ?: secondary.durationMs,
        description = primary.description ?: secondary.description,
        tags = (primary.tags + secondary.tags).distinct(),
        sourceLinks = primary.sourceLinks + secondary.sourceLinks,
        availability = mergeAvailability(primary.availability, secondary.availability),
    )

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
