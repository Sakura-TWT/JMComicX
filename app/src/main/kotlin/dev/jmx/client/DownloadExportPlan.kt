package dev.jmx.client

internal data class DownloadExportPlan(
    val chapters: Map<String, Set<String>>,
    val albumCount: Int,
    val chapterCount: Int,
    val pageCount: Int,
    val skippedChapters: Int,
    val sourceBytes: Long,
    val baseName: String,
) {
    fun extension(format: DownloadExportFormat) = if (format == DownloadExportFormat.PDF && chapterCount == 1) "pdf" else "zip"
    fun mimeType(format: DownloadExportFormat) = if (extension(format) == "pdf") "application/pdf" else "application/zip"
    fun fileName(format: DownloadExportFormat) = "$baseName.${extension(format)}"
}

internal fun downloadExportPlan(library: List<OfflineAlbum>, selectedIds: Set<String>): DownloadExportPlan {
    require(selectedIds.isNotEmpty()) { "请先选择要导出的漫画" }
    val albums = library.filter { it.id in selectedIds }
    require(albums.size == selectedIds.size) { "所选漫画已发生变化，请重新选择" }
    require(albums.all { album -> album.chapters.any { it.completed } }) { "部分漫画还没有完整章节，请下载完成后再导出" }
    val completed = albums.flatMap { it.chapters.filter(OfflineChapter::completed) }
    return DownloadExportPlan(
        chapters = albums.associate { album -> album.id to album.chapters.filter { it.completed }.mapTo(linkedSetOf()) { it.id } },
        albumCount = albums.size,
        chapterCount = completed.size,
        pageCount = completed.sumOf { it.downloadedPages },
        skippedChapters = albums.sumOf { it.chapters.size } - completed.size,
        sourceBytes = completed.sumOf { it.byteCount },
        baseName = if (albums.size == 1) downloadExportName(albums.single().title, "漫画") else "离线漫画合集",
    )
}
