package app.prismia.plus.core.chapter

import app.prismia.plus.core.download.DownloadObserver
import app.prismia.plus.core.download.ImageHttpHeaders
import app.prismia.plus.core.image.ImageDownloadRequest

fun ChapterTemplate.toImageDownloadRequests(
    headers: Map<String, String> = ImageHttpHeaders.default(refererHost = imageHost),
    acceptedContentTypes: Set<String> = setOf("image/*"),
    maxBytes: Long? = null,
    observerFactory: (index: Int, url: String) -> DownloadObserver = { _, _ -> DownloadObserver.None }
): List<ImageDownloadRequest> {
    return imageUrls.mapIndexed { index, url ->
        ImageDownloadRequest(
            sourceUrl = url,
            albumId = albumId,
            scrambleId = scrambleId,
            headers = headers,
            acceptedContentTypes = acceptedContentTypes,
            maxBytes = maxBytes,
            observer = observerFactory(index, url)
        )
    }
}
