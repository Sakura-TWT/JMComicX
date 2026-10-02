package app.prismia.plus

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import app.prismia.plus.core.api.AlbumChapter
import app.prismia.plus.core.api.AlbumDetail
import app.prismia.plus.core.image.ImagePlan

/** A local restored page cannot accidentally enter the remote URL/unscrambling pipeline. */
internal sealed interface ReaderPageResource {
    val cacheKey: String

    data class OnlineJm(
        val url: String,
        val plan: ImagePlan,
        val headers: NetworkHeaders,
    ) : ReaderPageResource {
        override val cacheKey: String get() = plan.cacheKey
    }

    data class RestoredLocal(val local: ReaderOfflineFile) : ReaderPageResource {
        override val cacheKey: String get() = local.cacheKey
    }
}

internal fun ReaderOfflineChapter.toReaderChapter(): AlbumChapter = AlbumChapter(id, name, sort)

/** Builds metadata entirely from the saved catalog; opening offline never needs album/detail APIs. */
internal fun readerOfflineLaunchRequest(
    album: HomeAlbum,
    manifest: ReaderOfflineManifest,
    initialChapterId: String,
    initialPageIndex: Int = 0,
): ReaderLaunchRequest = ReaderLaunchRequest(
    album = album,
    detail = AlbumDetail(
        id = album.id,
        name = album.name,
        description = null,
        authors = listOf(album.author),
        imageCount = null,
        totalViews = null,
        likes = null,
        commentTotal = null,
        tags = emptyList(),
        actors = emptyList(),
        works = emptyList(),
        isFavorite = null,
        liked = null,
        related = emptyList(),
        series = manifest.chapters.map { it.toReaderChapter() },
        seriesId = null,
        price = null,
        purchased = null,
        raw = emptyMap(),
    ),
    initialChapterId = initialChapterId,
    initialPageIndex = initialPageIndex,
    source = ReaderResourceSource.Offline(manifest),
)

internal fun ReaderOfflineInspection.readerChapterState(chapterId: String): ReaderChapterState {
    error?.let { return ReaderChapterState.Error(it) }
    val chapter = chapters.firstOrNull { it.chapter.id == chapterId }
        ?: return ReaderChapterState.Error("离线目录中没有此章节，不会请求网络。")
    chapter.error?.let { return ReaderChapterState.Error(it) }
    if (chapter.files.isEmpty()) return ReaderChapterState.Error("此章节没有离线图片，不会请求网络。")
    return ReaderChapterState.Content(pages = chapter.files.map { file ->
        ReaderPage(index = file.index, resource = ReaderPageResource.RestoredLocal(file))
    })
}

internal fun buildReaderOfflineImageRequest(
    context: Context,
    resource: ReaderPageResource.RestoredLocal,
    retryKey: Int,
    viewportWidthPx: Int,
): ImageRequest = ImageRequest.Builder(context)
    .data(resource.local.file)
    .networkCachePolicy(CachePolicy.DISABLED)
    .diskCachePolicy(CachePolicy.DISABLED)
    .allowHardware(true)
    .crossfade(false)
    .size(Size(viewportWidthPx, Dimension.Undefined))
    .memoryCacheKey("${resource.cacheKey}:reader:$viewportWidthPx:$retryKey")
    .build()
