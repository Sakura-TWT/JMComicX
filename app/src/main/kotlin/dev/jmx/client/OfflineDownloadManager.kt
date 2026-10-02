package dev.jmx.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import dev.jmx.client.core.api.AlbumChapter
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.chapter.toImageDownloadRequests
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Process singleton. One queue worker and one atomic metadata writer, with an export/file lease.
 * No work is scheduled from a receiver or an automatic sticky service restart.
 */
internal class OfflineDownloadManager private constructor(context: Context) {
    private val app = context.applicationContext
    val rootDirectory: File = File(app.filesDir, "offline/jm")
    private val metadata = File(rootDirectory, "library.json")
    private val exportCache = File(app.cacheDir, "offline-export")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = Mutex()
    private val commands = Mutex()
    private val worker = Mutex()
    private val mutableAlbums = MutableStateFlow<List<OfflineAlbum>>(emptyList())
    private val mutableReady = MutableStateFlow(false)
    private val mutableInitializationError = MutableStateFlow<String?>(null)
    val albums: StateFlow<List<OfflineAlbum>> = mutableAlbums.asStateFlow()
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    val initializationError: StateFlow<String?> = mutableInitializationError.asStateFlow()
    private var activeId: String? = null // Guarded by files.
    @Volatile private var activeJob: Job? = null

    init {
        scope.launch {
            runCatching { discardInterruptedDownloadExports(exportCache) }
            initialize()
        }
    }

    /** Startup and explicit retry use the same scan; never clear an error before a successful load. */
    private suspend fun initialize() {
        mutableReady.value = false
        try {
            files.withLock {
                check(rootDirectory.canonicalFile.toPath().startsWith(app.filesDir.canonicalFile.toPath())) {
                    "离线目录超出应用文件目录"
                }
                OfflineFiles.ensureDirectory(rootDirectory)
                check(!metadata.exists() || metadata.isFile) { "离线元数据不是普通文件" }
                val loaded = if (metadata.isFile) {
                    require(metadata.length() <= 32L * 1024 * 1024) { "离线元数据过大" }
                    OfflineMetadata.decode(metadata.readText())
                } else emptyList()
                val recovered = loaded.map { OfflineFiles.recover(rootDirectory, it) }
                // Only our temporary files are discarded; verified .ready receipts survive interruption.
                rootDirectory.walkTopDown().onEnter { directory ->
                    directory.canonicalFile.toPath().startsWith(rootDirectory.canonicalFile.toPath()) &&
                        !java.nio.file.Files.isSymbolicLink(directory.toPath())
                }.filter { it.isFile && (it.name.endsWith(".part") || it.name == "library.json.tmp" ||
                    it.name.endsWith(".ready.json.tmp")) }
                    .forEach { it.delete() }
                if (recovered != loaded) persist(recovered) else mutableAlbums.value = recovered
                mutableInitializationError.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableInitializationError.value = "离线下载记录无法读取，已保留原文件；请检查存储空间或备份后修复"
        } finally { mutableReady.value = true }
    }

    suspend fun enqueue(album: HomeAlbum, detail: AlbumDetail, chapterIds: Set<String>) = withContext(Dispatchers.IO) {
        awaitReady()
        commands.withLock {
            OfflineFiles.requireId(album.id)
            require(album.id == detail.id) { "漫画详情 ID 不匹配" }
            val directory = offlineDirectory(detail)
            require(chapterIds.isNotEmpty() && chapterIds.all { id -> directory.any { it.id == id } }) { "请选择有效章节" }
            directory.forEach { OfflineFiles.requireId(it.id) }
            files.withLock {
                val old = mutableAlbums.value.firstOrNull { it.id == album.id }
                val selected = directory.filter { it.id in chapterIds || old?.chapters?.any { c -> c.id == it.id } == true }
                val chapters = selected.map { chapter ->
                    old?.chapters?.firstOrNull { it.id == chapter.id }?.copy(
                        name = chapter.name?.takeIf { it.isNotBlank() } ?: album.name, sort = chapter.sort,
                    ) ?: OfflineChapter(chapter.id, chapter.name?.takeIf { it.isNotBlank() } ?: album.name, chapter.sort)
                }
                val updated = OfflineAlbum(album.id, detail.name?.takeIf { it.isNotBlank() } ?: album.name,
                    OfflineMetadata.sanitize(detail), chapters,
                    status = when {
                        chapters.all { it.completed } -> OfflineDownloadStatus.COMPLETED
                        old?.status == OfflineDownloadStatus.DOWNLOADING -> OfflineDownloadStatus.DOWNLOADING
                        else -> OfflineDownloadStatus.QUEUED
                    }, coverUrl = old?.coverUrl.orEmpty(), currentChapterId = old?.currentChapterId,
                    currentChapterName = old?.currentChapterName)
                persist(mutableAlbums.value.filterNot { it.id == album.id } + updated)
            }
            requestService()
        }
    }

    suspend fun pause(ids: Set<String>) = withContext(Dispatchers.IO) {
        awaitReady()
        commands.withLock { pauseInternal(ids, null) }
    }

    private suspend fun pauseInternal(ids: Set<String>, reason: String?, failureSafe: Boolean = false) =
        withContext(NonCancellable) {
            var running: Job? = null
            try {
                files.withLock {
                    running = activeJob.takeIf { activeId in ids }
                    val next = mutableAlbums.value.map { album ->
                        if (album.id in ids && album.status != OfflineDownloadStatus.COMPLETED) album.copy(
                            status = OfflineDownloadStatus.PAUSED, error = reason, updatedAt = System.currentTimeMillis(),
                            currentChapterId = null, currentChapterName = null,
                        ) else album
                    }
                    if (failureSafe) publishFailureSafe(next) else persist(next)
                }
            } finally {
                // A cancelled caller must not release commands while a page still owns its .part files.
                // Also stop the active task if persisting the pause failed.
                running?.cancelAndJoin()
            }
        }

    suspend fun resume(ids: Set<String>) = withContext(Dispatchers.IO) {
        awaitReady()
        commands.withLock {
            files.withLock {
                persist(mutableAlbums.value.map { album ->
                    if (album.id in ids && album.status != OfflineDownloadStatus.DOWNLOADING) {
                        val checked = OfflineFiles.recover(rootDirectory, album)
                        checked.copy(status = if (checked.completed) OfflineDownloadStatus.COMPLETED else OfflineDownloadStatus.QUEUED,
                            error = null, updatedAt = System.currentTimeMillis())
                    } else album
                })
            }
            requestService()
        }
    }

    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        awaitReady()
        commands.withLock {
            ids.forEach(OfflineFiles::requireId)
            // Pause + join BEFORE removing metadata/files, but never join while holding the file lease.
            pauseInternal(ids, null)
            files.withLock {
                // Removing files first leaves a recoverable FAILED task if deletion/persistence fails.
                ids.forEach { id ->
                    val directory = OfflineFiles.albumDirectory(rootDirectory, id)
                    if (directory.exists()) deleteTree(directory)
                }
                persist(mutableAlbums.value.filterNot { it.id in ids })
            }
        }
    }

    /** Invoke once from the visible app's startup. Never invoke from BOOT_COMPLETED/background. */
    suspend fun recoverPendingDownloads() = withContext(Dispatchers.IO) {
        awaitReady()
        commands.withLock { requestService() }
    }

    /** A failed startup scan keeps every operation closed, so retry must rescan before queueing again. */
    suspend fun retryInitialization() = withContext(Dispatchers.IO) {
        ready.first { it }
        commands.withLock {
            if (initializationError.value != null) initialize()
            check(initializationError.value == null) { initializationError.value.orEmpty() }
            requestService()
        }
    }

    suspend fun <T> withExportFiles(
        selectedChapters: Map<String, Set<String>>,
        block: suspend (List<DownloadExportAlbum>) -> T,
    ): T = withContext(Dispatchers.IO) {
        awaitReady()
        var snapshot: DownloadExportSnapshot? = null
        try {
            val captured = files.withLock {
                val selected = resolveDownloadExport(mutableAlbums.value, selectedChapters, ::pageFile)
                createDownloadExportSnapshot(selected, exportCache)
                    .also { snapshot = it }
            }
            // The pinned files survive deletion/replacement; encoding no longer blocks the download writer.
            block(captured.albums)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { snapshot?.close() }
        }
    }

    fun pageFile(page: OfflinePage): File = OfflineFiles.resolve(rootDirectory, page.relativePath)

    suspend fun readerManifest(albumId: String): ReaderOfflineManifest? = withContext(Dispatchers.IO) {
        awaitReady()
        files.withLock {
            mutableAlbums.value.firstOrNull { it.id == albumId }?.let { original ->
                val checked = OfflineFiles.recover(rootDirectory, original)
                val album = if (original.status == OfflineDownloadStatus.DOWNLOADING && checked.chapters == original.chapters)
                    original else checked
                if (album != original) replace(album)
                album.readerOfflineManifest(rootDirectory)
            }
        }
    }

    internal suspend fun runQueue() = withContext(Dispatchers.IO) {
        awaitReady()
        worker.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                val id = files.withLock { mutableAlbums.value.firstOrNull { it.status == OfflineDownloadStatus.QUEUED }?.id }
                    ?: break
                coroutineScope {
                    val task = launch(start = CoroutineStart.LAZY) { downloadAlbum(id) }
                    files.withLock {
                        if (mutableAlbums.value.any { it.id == id && it.status == OfflineDownloadStatus.QUEUED }) {
                            activeId = id
                            activeJob = task
                        } else task.cancel()
                    }
                    task.start()
                    task.join()
                    files.withLock { if (activeJob === task) { activeJob = null; activeId = null } }
                }
            }
        }
    }

    /** Synchronous cancellation is important: onTimeout must stopForeground/stopSelf immediately. */
    internal fun interruptForServiceStop(reason: String) {
        activeJob?.cancel(CancellationException(reason))
        scope.launch {
            ready.first { it }
            commands.withLock {
                files.withLock {
                    publishFailureSafe(mutableAlbums.value.map { album ->
                        if (album.status in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING))
                            album.copy(status = OfflineDownloadStatus.PAUSED, error = reason,
                                currentChapterId = null, currentChapterName = null) else album
                    })
                }
            }
        }
    }

    private suspend fun downloadAlbum(id: String) {
        try {
            files.withLock {
                val album = mutableAlbums.value.firstOrNull { it.id == id && it.status == OfflineDownloadStatus.QUEUED }
                    ?: return
                replace(album.copy(status = OfflineDownloadStatus.DOWNLOADING, error = null))
            }
            val core = createAppJmxCore(app)
            val adapter = OfflineImageAdapter(core.downloader)
            while (true) {
                currentCoroutineContext().ensureActive()
                val chapter = files.withLock {
                    val album = activeAlbum(id)
                    album.chapters.firstOrNull { !it.completed }?.also {
                        replace(album.copy(currentChapterId = it.id, currentChapterName = it.name))
                    }
                } ?: break
                val template = when (val result = core.chapterApi.template(chapter.id, shunt = DEFAULT_IMAGE_SHUNT)) {
                    is JmxResult.Success -> result.value
                    is JmxResult.Failure -> throw OfflineStorageException("章节信息获取失败，请检查网络后继续下载")
                }
                require(template.chapterId == chapter.id && template.imageFileNames.isNotEmpty()) { "章节信息不完整" }
                require(template.imageFileNames.size <= 100_000) { "章节页数超出支持范围" }
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(
                    (listOf(template.albumId.toString(), template.scrambleId.toString()) + template.imageFileNames)
                        .joinToString("\u0000").toByteArray(Charsets.UTF_8),
                ).joinToString("") { "%02x".format(it) }
                files.withLock {
                    val album = activeAlbum(id)
                    val latest = album.chapters.first { it.id == chapter.id }
                    val unchanged = latest.sourceFingerprint == fingerprint && latest.expectedPageCount == template.imageFileNames.size
                    replace(album.copy(chapters = album.chapters.map { entry ->
                        if (entry.id == chapter.id) entry.copy(expectedPageCount = template.imageFileNames.size,
                            sourceFingerprint = fingerprint, pages = if (unchanged) entry.pages else emptyList()) else entry
                    }))
                }
                val requests = template.toImageDownloadRequests(maxBytes = OfflineImageAdapter.MAX_COMPRESSED_BYTES)
                // 页面按 index 连续追加，所以已完成页数就是下一个缺口。
                val next = files.withLock { activeAlbum(id).chapters.first { it.id == chapter.id }.pages.size }
                val staging = OfflinePageStaging(rootDirectory, id, chapter.id, fingerprint)
                OfflineFiles.ensureDirectory(requireNotNull(staging.target(0).parentFile))
                OfflinePagePipeline(PAGE_CONCURRENCY).run(
                    startIndex = next,
                    pageCount = requests.size,
                    prepare = { index ->
                        // Verification, network, decode and hashing never hold the library/export lease.
                        staging.find(index) ?: run {
                            val image = adapter.download(requests[index], rootDirectory, staging.target(index))
                            currentCoroutineContext().ensureActive()
                            staging.save(index, image.file, image.extension)
                        }
                    },
                    publish = { staged ->
                        files.withLock {
                            currentCoroutineContext().ensureActive()
                            val album = activeAlbum(id)
                            val latest = album.chapters.first { it.id == chapter.id }
                            require(staged.withIndex().all { (offset, item) ->
                                item.page.index == latest.pages.size + offset
                            }) { "离线页面顺序不连续" }
                            val targets = staged.map(staging::promote)
                            replace(album.copy(chapters = album.chapters.map { entry ->
                                if (entry.id == chapter.id) entry.copy(pages = entry.pages + staged.map { it.page }) else entry
                            }, coverUrl = album.coverUrl.ifBlank { targets.first().toURI().toString() }))
                            staged.forEach { staging.committed(it.page.index) }
                        }
                    },
                    cleanup = staging::cleanupIncomplete,
                )
            }
            files.withLock {
                val album = activeAlbum(id)
                check(album.completed) { "章节下载不完整" }
                replace(album.copy(status = OfflineDownloadStatus.COMPLETED, currentChapterId = null, currentChapterName = null))
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                files.withLock {
                    mutableAlbums.value.firstOrNull { it.id == id && it.status == OfflineDownloadStatus.DOWNLOADING }?.let {
                        publishFailureSafe(mutableAlbums.value.map { album -> if (album.id == id) album.copy(
                            status = OfflineDownloadStatus.PAUSED, error = "下载已中断，可手动继续",
                            currentChapterId = null, currentChapterName = null) else album })
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            files.withLock {
                publishFailureSafe(mutableAlbums.value.map { album -> if (album.id == id && album.status == OfflineDownloadStatus.DOWNLOADING)
                    album.copy(status = OfflineDownloadStatus.FAILED, error = offlineErrorMessage(error),
                        currentChapterId = null, currentChapterName = null) else album })
            }
        }
    }

    private fun activeAlbum(id: String): OfflineAlbum = mutableAlbums.value.firstOrNull {
        it.id == id && it.status == OfflineDownloadStatus.DOWNLOADING
    } ?: throw CancellationException("任务已暂停或删除")

    private fun replace(album: OfflineAlbum) = persist(mutableAlbums.value.map {
        if (it.id == album.id) album.copy(updatedAt = System.currentTimeMillis()) else it
    })

    /** Caller holds files: sync + rename first, then expose progress. */
    private fun persist(next: List<OfflineAlbum>) {
        try {
            OfflineFiles.atomicWrite(metadata, OfflineMetadata.encode(next))
            mutableAlbums.value = next
        } catch (error: Exception) { throw OfflineStorageException(offlineErrorMessage(error), error) }
    }

    private fun publishFailureSafe(next: List<OfflineAlbum>) {
        try { persist(next) } catch (error: Exception) {
            mutableAlbums.value = next.map { album ->
                if (album.status != OfflineDownloadStatus.COMPLETED) album.copy(status = OfflineDownloadStatus.FAILED,
                    error = "下载进度保存失败，请释放存储空间后继续") else album
            }
        }
    }

    private suspend fun awaitReady() {
        ready.first { it }
        check(initializationError.value == null) { initializationError.value.orEmpty() }
    }

    private suspend fun requestService() {
        if (mutableAlbums.value.none { it.status == OfflineDownloadStatus.QUEUED || it.status == OfflineDownloadStatus.DOWNLOADING }) return
        try { app.startForegroundService(Intent(app, OfflineDownloadService::class.java)) }
        catch (error: RuntimeException) {
            files.withLock {
                publishFailureSafe(mutableAlbums.value.map { album ->
                    if (album.status == OfflineDownloadStatus.QUEUED) album.copy(status = OfflineDownloadStatus.PAUSED,
                        error = "系统暂不允许前台下载，请返回应用后继续") else album
                })
            }
            throw OfflineStorageException("系统暂不允许前台下载，请返回应用后继续", error)
        }
    }

    private fun deleteTree(directory: File) {
        // Never follow symlinks, including paths planted by a damaged restored backup.
        if (java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            java.nio.file.Files.delete(directory.toPath())
            return
        }
        directory.listFiles()?.forEach { entry ->
            if (entry.isDirectory && !java.nio.file.Files.isSymbolicLink(entry.toPath())) deleteTree(entry)
            else check(entry.delete()) { "无法删除离线文件" }
        }
        check(directory.delete()) { "无法删除离线目录" }
    }

    companion object {
        /** Matches core's DownloadBatchRunner.DEFAULT_CONCURRENCY; decoding stays serial in the adapter. */
        private const val PAGE_CONCURRENCY = 4

        // Holds the application context on purpose: the queue must outlive any activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: OfflineDownloadManager? = null
        fun get(context: Context): OfflineDownloadManager = instance ?: synchronized(this) {
            instance ?: OfflineDownloadManager(context).also { instance = it }
        }
    }
}

private fun offlineDirectory(detail: AlbumDetail): List<AlbumChapter> = detail.series.ifEmpty {
    listOf(AlbumChapter(detail.id, detail.name, "1"))
}.distinctBy { it.id }

private fun offlineErrorMessage(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    if (chain.any { it.message?.contains("ENOSPC", true) == true || it.message?.contains("No space left", true) == true ||
            it.message?.contains("存储空间不足") == true }) return "存储空间不足，请释放空间后继续下载"
    val message = chain.filterIsInstance<OfflineStorageException>().firstOrNull()?.message
        ?: "下载未完成，请检查网络或存储空间后重试"
    val page = chain.filterIsInstance<OfflinePageFailure>().firstOrNull()
    return if (page != null) "第 ${page.pageIndex + 1} 页下载失败\n$message" else message
}
