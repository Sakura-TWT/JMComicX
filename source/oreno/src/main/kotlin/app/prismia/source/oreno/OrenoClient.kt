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
        require(ORENO_MOVIE_ID.matches(key.remoteId)) { "invalid Oreno movie ID" }
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
        // Convert a logical offset to fixed-size source pages. A 32-item
        // consumer must not discard items 33..36 when advancing to page two.
        val offset = page.toLong() * limit
        val firstSourcePage = offset / ORENO_PAGE_SIZE + 1
        require(firstSourcePage <= Int.MAX_VALUE - 4L) { "page exceeds the Oreno range" }
        var sourcePage = firstSourcePage.toInt()
        var skip = (offset % ORENO_PAGE_SIZE).toInt()
        val items = ArrayList<VideoWork>(limit)
        while (true) {
            val html = transport.get(path, query + mapOf("page" to sourcePage.toString()))
            val native = parser.parsePage(html, sourcePage)
            if (native.hasMore && native.items.size != ORENO_PAGE_SIZE) {
                throw OrenoParseException("Oreno page size changed; refusing to skip unknown items")
            }
            val remaining = native.items.drop(skip)
            val consumed = minOf(limit - items.size, remaining.size)
            items += remaining.take(consumed).map { it.toWork() }
            val hasMore = remaining.size > consumed || native.hasMore
            if (items.size == limit || !native.hasMore) return VideoPage(items, page, hasMore)
            sourcePage++
            skip = 0
        }
    }

    private fun OrenoVideoRecord.toWork() = VideoWork(
        key = ContentKey(ContentType.VIDEO, ContentSource.ORENO3D, id),
        title = title,
        author = author,
        coverUrl = thumbnailUrl,
        description = description,
        tags = tags,
        // A link proves identity, not that the upstream stream still exists.
        availability = VideoAvailability.UNKNOWN,
        sourceLinks = buildMap { iwaraVideoId?.let { put(ContentSource.IWARA, it) }; put(ContentSource.ORENO3D, id) },
    )
}
