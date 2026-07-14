package dev.jmx.client.core.download

import dev.jmx.client.core.chapter.ChapterImageTransferOptions
import dev.jmx.client.core.chapter.ChapterImageTransferReport
import dev.jmx.client.core.chapter.ChapterImageTransferRunner
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.image.FileImageOutputStore
import dev.jmx.client.core.image.ImageIoRowCodec
import dev.jmx.client.core.image.ImageOutputStore
import dev.jmx.client.core.image.ImageRestoreBatchRunner
import dev.jmx.client.core.image.ImageRestoreExecutor
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.ImageStoreBatchRunner
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class TaskExecutionPolicy(
    val maxConcurrentTasks: Int = 2,
    val minIntervalBetweenTaskStartsMillis: Long = 0L
)

internal object ChapterDownloadResumeSupport {
    fun safeStem(originalFileName: String): String {
        val stem = originalFileName.substringBeforeLast('.', originalFileName)
        return stem.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "image" }
    }

    fun isOutputPresentForImage(existingFileNames: Collection<String>, originalFileName: String): Boolean {
        val safe = Regex.escape(safeStem(originalFileName))
        val pattern = Regex(
            """^\d{5}_${safe}_[A-Za-z0-9._-]+\.[A-Za-z0-9]+$""",
            RegexOption.IGNORE_CASE
        )
        return existingFileNames.any { pattern.matches(it) }
    }

    fun existingCompletedNames(outputDir: Path, imageFileNames: List<String>): Set<String> {
        if (!Files.isDirectory(outputDir)) return emptySet()
        return runCatching {
            val existing = Files.newDirectoryStream(outputDir).use { dirStream ->
                dirStream.map { it.fileName.toString() }
            }
            imageFileNames.filter { isOutputPresentForImage(existing, it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun successfullyStoredFileNames(
        workImageFileNames: List<String>,
        report: ChapterImageTransferReport
    ): List<String> {
        val storedOk = report.storeResults
            .filter { it.result is JmxResult.Success }
            .map { it.item.index }
            .toHashSet()
        return report.restoreResults.mapNotNull { restoreItem ->
            when (restoreItem.result) {
                is JmxResult.Success -> {
                    if (restoreItem.item.index in storedOk) {
                        workImageFileNames.getOrNull(restoreItem.item.index)
                    } else {
                        null
                    }
                }
                is JmxResult.Failure -> null
            }
        }
    }
}

enum class ChapterDownloadTaskState {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled
}

data class ChapterDownloadTaskSpec(
    val albumId: String,
    val chapterId: String,
    val shunt: String = "1",
    val outputDirectory: Path,
    val maxImages: Int? = null,
    val maxImageBytes: Long? = 20L * 1024L * 1024L
)

data class ChapterDownloadTaskSnapshot(
    val id: String,
    val spec: ChapterDownloadTaskSpec,
    val state: ChapterDownloadTaskState,
    val progress: ChapterDownloadProgress?,
    val error: JmxError?,
    val report: ChapterImageTransferReport?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,

    val completedImageFileNames: List<String> = emptyList()
)

data class ChapterDownloadProgress(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val bytesWritten: Long
) {
    val ratio: Double = if (total <= 0) 0.0 else completed.toDouble() / total.toDouble()
}

fun interface ChapterDownloadTaskListener {
    fun onChanged(snapshot: ChapterDownloadTaskSnapshot)
}

class ChapterDownloadTaskManager(
    private val templateFetcher: suspend (chapterId: String, shunt: String) -> JmxResult<ChapterTemplate>,
    private val downloader: Downloader,
    private val imageRowCodec: ImageRowCodec = ImageIoRowCodec(),
    private val downloadConcurrency: Int = 4,
    private val taskStore: ChapterDownloadTaskStore? = null,
    private val outputStoreFactory: (Path) -> ImageOutputStore = { FileImageOutputStore(it) },
    private val executionPolicy: TaskExecutionPolicy = TaskExecutionPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val tasks = ConcurrentHashMap<String, TaskRecord>()
    private val listeners = ConcurrentHashMap.newKeySet<ChapterDownloadTaskListener>()
    private val taskSlots = Semaphore(executionPolicy.maxConcurrentTasks.coerceAtLeast(1))
    private val activeTaskCount = AtomicInteger(0)
    private val lastTaskStartAt = AtomicLong(0L)
    private val startGate = Mutex()

    val peakConcurrentTasks: Int
        get() = peakActive.get()
    private val peakActive = AtomicInteger(0)

    init {
        taskStore?.loadAll()?.forEach { persisted ->
            val snapshot = persisted.toSnapshot()
            tasks[snapshot.id] = TaskRecord(snapshot = snapshot)
        }
    }

    fun addListener(listener: ChapterDownloadTaskListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ChapterDownloadTaskListener) {
        listeners.remove(listener)
    }

    suspend fun enqueue(spec: ChapterDownloadTaskSpec): ChapterDownloadTaskSnapshot {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val snapshot = ChapterDownloadTaskSnapshot(
            id = id,
            spec = spec,
            state = ChapterDownloadTaskState.Pending,
            progress = null,
            error = null,
            report = null,
            createdAtMillis = now,
            updatedAtMillis = now,
            completedImageFileNames = emptyList()
        )
        val record = TaskRecord(snapshot = snapshot)
        tasks[id] = record
        persist(record)
        notify(snapshot)
        return snapshot
    }

    suspend fun start(taskId: String): JmxResult<ChapterDownloadTaskSnapshot> = mutex.withLock {
        val record = tasks[taskId]
            ?: return JmxResult.Failure(JmxError.Schema("任务不存在", field = "taskId"))
        if (record.snapshot.state == ChapterDownloadTaskState.Running) {
            return JmxResult.Success(record.snapshot)
        }
        if (record.snapshot.state == ChapterDownloadTaskState.Completed) {
            return JmxResult.Success(record.snapshot)
        }
        record.cancelRequested.set(false)
        update(record) {
            it.copy(
                state = ChapterDownloadTaskState.Running,
                error = null,
                report = null,
                updatedAtMillis = nowMillis()
            )
        }
        record.job = scope.launch {
            taskSlots.withPermit {
                awaitStartBudget()
                val running = activeTaskCount.incrementAndGet()
                peakActive.updateAndGet { maxOf(it, running) }
                try {
                    runTask(record)
                } finally {
                    activeTaskCount.decrementAndGet()
                }
            }
        }
        JmxResult.Success(record.snapshot)
    }

    private suspend fun awaitStartBudget() {
        val minInterval = executionPolicy.minIntervalBetweenTaskStartsMillis.coerceAtLeast(0L)
        startGate.withLock {
            if (minInterval > 0L) {
                val last = lastTaskStartAt.get()
                val now = nowMillis()
                val wait = last + minInterval - now
                if (wait > 0L) {
                    delay(wait)
                }
            }
            lastTaskStartAt.set(nowMillis())
        }
    }

    suspend fun cancel(taskId: String): JmxResult<ChapterDownloadTaskSnapshot> = mutex.withLock {
        val record = tasks[taskId]
            ?: return JmxResult.Failure(JmxError.Schema("任务不存在", field = "taskId"))
        record.cancelRequested.set(true)
        record.job?.cancel()
        if (record.snapshot.state == ChapterDownloadTaskState.Running ||
            record.snapshot.state == ChapterDownloadTaskState.Pending
        ) {
            update(record) {
                it.copy(
                    state = ChapterDownloadTaskState.Cancelled,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
        }
        JmxResult.Success(record.snapshot)
    }

    fun get(taskId: String): ChapterDownloadTaskSnapshot? = tasks[taskId]?.snapshot

    fun list(): List<ChapterDownloadTaskSnapshot> =
        tasks.values.map { it.snapshot }.sortedBy { it.createdAtMillis }

    fun reloadFromStore() {
        val store = taskStore ?: return
        val loaded = store.loadAll().map { it.toSnapshot() }
        loaded.forEach { snapshot ->
            val existing = tasks[snapshot.id]
            if (existing == null || existing.snapshot.state != ChapterDownloadTaskState.Running) {
                tasks[snapshot.id] = TaskRecord(snapshot = snapshot)
            }
        }
    }

    private suspend fun runTask(record: TaskRecord) {
        val spec = record.snapshot.spec
        if (record.cancelRequested.get()) {
            finishCancelled(record)
            return
        }
        val templateResult = templateFetcher(spec.chapterId, spec.shunt)
        val template = when (templateResult) {
            is JmxResult.Success -> templateResult.value
            is JmxResult.Failure -> {
                fail(record, templateResult.error)
                return
            }
        }
        if (record.cancelRequested.get()) {
            finishCancelled(record)
            return
        }
        val alreadyDone = record.snapshot.completedImageFileNames.toMutableSet()

        alreadyDone += ChapterDownloadResumeSupport.existingCompletedNames(
            spec.outputDirectory,
            template.imageFileNames
        )

        val limitedAll = if (spec.maxImages != null) {
            template.imageFileNames.take(spec.maxImages.coerceAtLeast(0))
        } else {
            template.imageFileNames
        }
        val remaining = limitedAll.filterNot { it in alreadyDone }
        val workTemplate = template.copy(imageFileNames = remaining)

        update(record) {
            it.copy(
                progress = ChapterDownloadProgress(
                    total = limitedAll.size,
                    completed = limitedAll.size - remaining.size,
                    failed = 0,
                    bytesWritten = 0
                ),
                completedImageFileNames = alreadyDone.filter { it in limitedAll },
                updatedAtMillis = System.currentTimeMillis()
            )
        }

        if (remaining.isEmpty()) {
            update(record) {
                it.copy(
                    state = ChapterDownloadTaskState.Completed,
                    progress = ChapterDownloadProgress(
                        total = limitedAll.size,
                        completed = limitedAll.size,
                        failed = 0,
                        bytesWritten = it.progress?.bytesWritten ?: 0L
                    ),
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            return
        }

        val store = outputStoreFactory(spec.outputDirectory)
        val runner = ChapterImageTransferRunner(
            restoreBatchRunner = ImageRestoreBatchRunner(
                executor = ImageRestoreExecutor(downloader, imageRowCodec),
                maxConcurrency = downloadConcurrency
            ),
            storeBatchRunner = ImageStoreBatchRunner(store)
        )
        val report = try {
            runner.transfer(
                template = workTemplate,
                options = ChapterImageTransferOptions(
                    headers = null,
                    maxBytes = spec.maxImageBytes
                )
            )
        } catch (t: Throwable) {
            fail(record, JmxError.Unknown(t.message ?: "下载任务异常", t))
            return
        }

        val batchDone = ChapterDownloadResumeSupport.successfullyStoredFileNames(
            workImageFileNames = workTemplate.imageFileNames,
            report = report
        )
        if (record.cancelRequested.get()) {
            update(record) {
                it.copy(
                    completedImageFileNames = (it.completedImageFileNames + batchDone).distinct(),
                    state = ChapterDownloadTaskState.Cancelled,
                    report = report,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            return
        }

        val allDone = (alreadyDone.filter { it in limitedAll } + batchDone).distinct()
        val progress = ChapterDownloadProgress(
            total = limitedAll.size,
            completed = allDone.size,
            failed = report.failedCount,
            bytesWritten = report.restoreResults.sumOf { item ->
                when (val r = item.result) {
                    is JmxResult.Success -> r.value.bytes.size.toLong()
                    is JmxResult.Failure -> 0L
                }
            }
        )
        val state = when {
            report.failedCount == 0 && allDone.size >= limitedAll.size && limitedAll.isNotEmpty() ->
                ChapterDownloadTaskState.Completed
            limitedAll.isEmpty() -> ChapterDownloadTaskState.Failed
            report.failedCount > 0 && batchDone.isEmpty() && alreadyDone.none { it in limitedAll } ->
                ChapterDownloadTaskState.Failed
            report.failedCount > 0 -> ChapterDownloadTaskState.Failed
            else -> ChapterDownloadTaskState.Completed
        }
        val error = if (state == ChapterDownloadTaskState.Failed) {
            report.storeResults.firstOrNull { it.result is JmxResult.Failure }
                ?.let { (it.result as JmxResult.Failure).error }
                ?: report.restoreResults.firstOrNull { it.result is JmxResult.Failure }
                    ?.let { (it.result as JmxResult.Failure).error }
                ?: JmxError.Schema("章节无图片可下载")
        } else {
            null
        }
        update(record) {
            it.copy(
                state = state,
                progress = progress,
                report = report,
                error = error,
                completedImageFileNames = allDone,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private fun finishCancelled(record: TaskRecord) {
        update(record) {
            it.copy(
                state = ChapterDownloadTaskState.Cancelled,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private fun fail(record: TaskRecord, error: JmxError) {
        update(record) {
            it.copy(
                state = ChapterDownloadTaskState.Failed,
                error = error,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private fun update(record: TaskRecord, transform: (ChapterDownloadTaskSnapshot) -> ChapterDownloadTaskSnapshot) {
        val next = transform(record.snapshot)
        record.snapshot = next
        persist(record)
        notify(next)
    }

    private fun persist(record: TaskRecord) {
        val store = taskStore ?: return
        runCatching {
            store.save(record.snapshot.toPersisted(record.snapshot.completedImageFileNames))
        }
    }

    private fun notify(snapshot: ChapterDownloadTaskSnapshot) {
        listeners.forEach { listener ->
            runCatching { listener.onChanged(snapshot) }
        }
    }

    private class TaskRecord(
        @Volatile var snapshot: ChapterDownloadTaskSnapshot,
        @Volatile var job: Job? = null,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false)
    )
}
