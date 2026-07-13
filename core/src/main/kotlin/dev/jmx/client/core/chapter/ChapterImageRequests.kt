package dev.jmx.client.core.chapter

import dev.jmx.client.core.download.DownloadObserver
import dev.jmx.client.core.image.ImageDownloadRequest

fun ChapterTemplate.toImageDownloadRequests(
    headers: Map<String, String> = emptyMap(),
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
