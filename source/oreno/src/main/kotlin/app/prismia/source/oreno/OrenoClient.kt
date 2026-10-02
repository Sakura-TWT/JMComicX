package app.prismia.source.oreno

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.PagedVideoCatalog
import app.prismia.video.VideoAvailability
import app.prismia.video.VideoDetail
import app.prismia.video.VideoPage
import app.prismia.video.VideoWork

class OrenoClient(
    private val transport: OrenoTransport,
    private val parser: OrenoHtmlParser = OrenoHtmlParser(),
) : PagedVideoCatalog {
    override suspend fun browse(page: Int, limit: Int): VideoPage =
        fetchPage("/movies", page, limit)

    override suspend fun searchPage(query: String, page: Int, limit: Int): VideoPage =
        fetchPage("/search", page, limit, mapOf("keyword" to query))

    override suspend fun find(query: String): List<VideoWork> = searchPage(query).items

    override suspend fun detail(key: ContentKey): VideoWork = detailPage(key).work

    override suspend fun detailPage(key: ContentKey): VideoDetail {
        require(key.contentType == ContentType.VIDEO) { "OrenoClient requires a VIDEO content key" }
        require(key.source == ContentSource.ORENO3D) { "OrenoClient requires an ORENO3D content key" }
        val html = transport.get("/movies/${key.remoteId}")
        val record = parser.parseDetail(html, key.remoteId)
            ?: throw OrenoParseException("oreno3d detail did not contain movie ${key.remoteId}")
        return VideoDetail(record.toWork())
    }

    private suspend fun fetchPage(
        path: String,
        page: Int,
        limit: Int,
        query: Map<String, String> = emptyMap(),
    ): VideoPage {
        require(page >= 0) { "page must be non-negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val html = transport.get(path, query + mapOf("page" to (page + 1).toString()))
        val items = parser.parseCards(html).take(limit).map { it.toWork() }
        return VideoPage(items = items, page = page, hasMore = items.size >= limit)
    }

    private fun OrenoVideoRecord.toWork() = VideoWork(
        key = ContentKey(ContentType.VIDEO, ContentSource.ORENO3D, id),
        title = title,
        author = author,
        coverUrl = thumbnailUrl,
        tags = tags,
        availability = if (iwaraVideoId?.isNotBlank() == true) VideoAvailability.PLAYABLE else VideoAvailability.UNKNOWN,
        sourceLinks = buildMap { iwaraVideoId?.let { put(ContentSource.IWARA, it) }; put(ContentSource.ORENO3D, id) },
    )
}
