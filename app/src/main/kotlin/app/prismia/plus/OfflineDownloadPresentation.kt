package app.prismia.plus

internal fun OfflineAlbum.toDownloadUiModel(): OfflineDownloadUiModel {
    val current = chapters.firstOrNull { it.id == currentChapterId }
    val pageCountKnown = chapters.isNotEmpty() && chapters.all { it.expectedPageCount > 0 }
    return OfflineDownloadUiModel(
        album = toHomeAlbum(),
        downloadedChapters = completedChapters,
        totalChapters = totalChapters,
        progress = if (pageCountKnown && totalPages > 0) downloadedPages.toFloat() / totalPages else null,
        statusLabel = when (status) {
            OfflineDownloadStatus.QUEUED -> "等待下载"
            OfflineDownloadStatus.DOWNLOADING -> "下载中"
            OfflineDownloadStatus.PAUSED -> "已暂停"
            OfflineDownloadStatus.COMPLETED -> "已完成"
            OfflineDownloadStatus.FAILED -> "下载失败"
        },
        canPause = status in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING),
        canResume = status == OfflineDownloadStatus.PAUSED,
        canRetry = status == OfflineDownloadStatus.FAILED,
        error = error,
        chapterLabel = currentChapterName?.takeIf { status == OfflineDownloadStatus.DOWNLOADING && it.isNotBlank() },
        pageLabel = current?.takeIf { status == OfflineDownloadStatus.DOWNLOADING && it.expectedPageCount > 0 }
            ?.let { "本章 ${it.downloadedPages} / ${it.expectedPageCount} 页" },
    )
}

internal fun offlineRetryIds(items: List<OfflineDownloadUiModel>): Set<String> =
    items.filter { it.canRetry }.mapTo(linkedSetOf()) { it.id }

internal fun canExportOfflineSelection(items: List<OfflineDownloadUiModel>, selectedIds: Set<String>): Boolean {
    if (selectedIds.isEmpty()) return false
    val exportableIds = items.filter { it.downloadedChapters > 0 }.mapTo(hashSetOf()) { it.id }
    return exportableIds.containsAll(selectedIds)
}
