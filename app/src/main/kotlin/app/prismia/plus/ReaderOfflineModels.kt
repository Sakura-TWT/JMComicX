package app.prismia.plus

import java.io.File

/** Resource provenance belongs to a reader launch, never to a global offline flag. */
internal sealed interface ReaderResourceSource {
    data object OnlineJm : ReaderResourceSource
    data class Offline(val manifest: ReaderOfflineManifest) : ReaderResourceSource
}

/** The offline catalog contains only complete, committed chapters in their original reading order. */
internal data class ReaderOfflineManifest(
    val albumId: String,
    val rootDirectory: File,
    val chapters: List<ReaderOfflineChapter>,
)

internal fun OfflineAlbum.readerOfflineManifest(rootDirectory: File): ReaderOfflineManifest {
    val completed = chapters.filter(OfflineChapter::completed).associateBy { it.id }
    val order = (detail.series.map { it.id } + chapters.map { it.id }).distinct()
    return ReaderOfflineManifest(id, rootDirectory, order.mapNotNull { chapterId ->
        completed[chapterId]?.let { chapter ->
            ReaderOfflineChapter(chapter.id, chapter.name, chapter.sort, chapter.expectedPageCount,
                chapter.pages.map { ReaderOfflinePage(it.index, it.relativePath, it.byteCount) })
        }
    })
}

internal data class ReaderOfflineChapter(
    val id: String,
    val name: String?,
    val sort: String?,
    val expectedPageCount: Int,
    val pages: List<ReaderOfflinePage> = emptyList(),
)

/** Only committed, already-restored image files belong here; never raw downloads or remote URLs. */
internal data class ReaderOfflinePage(
    val index: Int,
    val relativePath: String,
    val byteCount: Long,
)

internal data class ReaderOfflineFile(
    val index: Int,
    val file: File,
    val byteCount: Long,
    val modifiedAt: Long,
) {
    // Cannot collide with JM's remote/scrambled cache, even for the same chapter and page.
    val cacheKey: String = "offline-restored:${file.path}:$byteCount:$modifiedAt"
}

internal data class ReaderOfflineChapterFiles(
    val chapter: ReaderOfflineChapter,
    val files: List<ReaderOfflineFile>,
    val error: String? = null,
)

internal data class ReaderOfflineInspection(
    val chapters: List<ReaderOfflineChapterFiles>,
    val error: String? = null,
) {
    val availableChapterIds: Set<String> = chapters
        .filter { it.error == null && it.files.isNotEmpty() }
        .mapTo(linkedSetOf()) { it.chapter.id }
}

/** Metadata-only validation; safe during composition. Disk validation is done on Dispatchers.IO. */
internal fun ReaderOfflineManifest.catalogError(expectedAlbumId: String): String? = when {
    albumId.isBlank() || albumId != expectedAlbumId -> "离线清单与当前漫画不匹配，不会请求网络。"
    chapters.isEmpty() -> "暂无完整下载的章节，请返回离线下载页查看进度。"
    chapters.any { it.id.isBlank() } || chapters.map { it.id }.distinct().size != chapters.size ->
        "离线章节目录无效，请重新下载，不会请求网络。"
    else -> null
}

/** Never fill missing pages using a remote template, directory scan, or a different chapter. */
internal fun inspectReaderOfflineManifest(
    manifest: ReaderOfflineManifest,
    expectedAlbumId: String = manifest.albumId,
): ReaderOfflineInspection {
    manifest.catalogError(expectedAlbumId)?.let { return ReaderOfflineInspection(emptyList(), it) }
    val root = try {
        manifest.rootDirectory.canonicalFile.takeIf { manifest.rootDirectory.isAbsolute && it.isDirectory }
    } catch (_: Exception) {
        null
    } ?: return ReaderOfflineInspection(emptyList(), "离线目录不存在或无法读取，不会请求网络。")
    return ReaderOfflineInspection(manifest.chapters.map { chapter ->
        fun invalid(message: String) = ReaderOfflineChapterFiles(chapter, emptyList(), message)
        val pages = chapter.pages.sortedBy { it.index }
        if (chapter.expectedPageCount <= 0 || pages.size != chapter.expectedPageCount ||
            pages.map { it.index } != pages.indices.toList()
        ) {
            return@map invalid("此章节尚未完整下载，离线模式不会请求网络。")
        }
        val files = ArrayList<ReaderOfflineFile>(pages.size)
        val uniqueFiles = HashSet<File>()
        for (page in pages) {
            val relative = page.relativePath.replace('\\', '/')
            val parts = relative.split('/')
            if (relative.isBlank() || relative.startsWith('/') || ':' in relative || '\u0000' in relative ||
                parts.any { it.isEmpty() || it == "." || it == ".." } || page.byteCount <= 0L ||
                relative.substringAfterLast('.', "").lowercase() !in READER_OFFLINE_IMAGE_EXTENSIONS
            ) {
                return@map invalid("离线图片路径或文件记录无效，请重新下载，不会请求网络。")
            }
            val file = try {
                File(root, relative).canonicalFile
            } catch (_: Exception) {
                return@map invalid("离线图片路径无法读取，不会请求网络。")
            }
            try {
                if (!file.toPath().startsWith(root.toPath()) || !uniqueFiles.add(file) ||
                    !file.isFile || !file.canRead() || file.length() != page.byteCount
                ) {
                    return@map invalid("离线图片缺失、损坏或越出下载目录，请重新下载，不会请求网络。")
                }
                files += ReaderOfflineFile(page.index, file, page.byteCount, file.lastModified())
            } catch (_: Exception) {
                return@map invalid("离线图片无法读取，不会请求网络。")
            }
        }
        ReaderOfflineChapterFiles(chapter, files)
    })
}

/** Skip undownloaded catalog entries, but never leave the saved catalog or wrap around. */
internal fun readerOfflineAdjacentChapterIndex(
    chapters: List<ReaderOfflineChapter>,
    availableChapterIds: Set<String>,
    currentIndex: Int,
    direction: Int,
): Int? {
    if (currentIndex !in chapters.indices || direction !in listOf(-1, 1)) return null
    var index = currentIndex + direction
    while (index in chapters.indices) {
        if (chapters[index].id in availableChapterIds) return index
        index += direction
    }
    return null
}

private val READER_OFFLINE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heif", "heic")
