package app.prismia.plus

import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystemException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Final pages are atomically replaced, never edited in place, so hard links form a stable export view. */
internal class DownloadExportSnapshot(val albums: List<DownloadExportAlbum>, private val directory: File) : AutoCloseable {
    override fun close() { directory.deleteRecursively() }
}

// Only call during process startup, before any export can acquire a snapshot.
internal fun discardInterruptedDownloadExports(cache: File) {
    if (Files.isSymbolicLink(cache.toPath())) return
    val snapshotName = Regex("export-[0-9]+")
    val pageName = Regex("[0-9]+-[0-9]+-[0-9]+\\.[a-zA-Z0-9]+")
    cache.listFiles()?.filter { snapshotName.matches(it.name) && it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        ?.forEach { directory ->
            val pages = directory.listFiles() ?: return@forEach
            if (pages.all { pageName.matches(it.name) && !it.isDirectory }) {
                pages.forEach { it.delete() }
                directory.delete()
            }
        }
}

internal suspend fun createDownloadExportSnapshot(
    albums: List<DownloadExportAlbum>,
    cache: File,
    link: (File, File) -> Unit = { source, target -> Files.createLink(target.toPath(), source.toPath()); Unit },
): DownloadExportSnapshot {
    OfflineFiles.ensureDirectory(cache)
    val directory = Files.createTempDirectory(cache.toPath(), "export-").toFile()
    try {
        val buffer = ByteArray(64 * 1024)
        val snapshot = albums.mapIndexed { albumIndex, album ->
            album.copy(chapters = album.chapters.mapIndexed { chapterIndex, chapter ->
                chapter.copy(pages = chapter.pages.mapIndexed { pageIndex, source ->
                    currentCoroutineContext().ensureActive()
                    require(source.isFile && source.length() > 0) { "部分章节文件已丢失，请重新下载后导出" }
                    val target = File(directory, "$albumIndex-$chapterIndex-$pageIndex.${source.extension}")
                    val linked = try { link(source, target); true }
                    catch (_: UnsupportedOperationException) { false }
                    catch (_: FileSystemException) { false }
                    if (!linked) {
                        // Some document/cache filesystems cannot hard-link. Copy only the selected pages.
                        OfflineFiles.ensureSpace(cache, source.length())
                        source.inputStream().use { input ->
                            target.outputStream().use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    target
                })
            })
        }
        currentCoroutineContext().ensureActive()
        return DownloadExportSnapshot(snapshot, directory)
    } catch (error: Throwable) {
        directory.deleteRecursively()
        throw error
    }
}
