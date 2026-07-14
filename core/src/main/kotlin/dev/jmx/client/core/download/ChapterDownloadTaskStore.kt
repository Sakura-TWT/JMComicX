package dev.jmx.client.core.download

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.jmx.client.core.result.JmxError
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class PersistedChapterDownloadTask(
    val id: String,
    val albumId: String,
    val chapterId: String,
    val shunt: String,
    val outputDirectory: String,
    val maxImages: Int?,
    val maxImageBytes: Long?,
    val state: String,
    val progressTotal: Int?,
    val progressCompleted: Int?,
    val progressFailed: Int?,
    val progressBytesWritten: Long?,
    val errorMessage: String?,

    val completedImageFileNames: List<String> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

interface ChapterDownloadTaskStore {
    fun loadAll(): List<PersistedChapterDownloadTask>
    fun saveAll(tasks: List<PersistedChapterDownloadTask>)
    fun save(task: PersistedChapterDownloadTask)
    fun delete(taskId: String)
    fun clear()
}

class InMemoryChapterDownloadTaskStore : ChapterDownloadTaskStore {
    private val lock = Any()
    private val tasks = linkedMapOf<String, PersistedChapterDownloadTask>()

    override fun loadAll(): List<PersistedChapterDownloadTask> = synchronized(lock) {
        tasks.values.toList()
    }

    override fun saveAll(tasks: List<PersistedChapterDownloadTask>) = synchronized(lock) {
        this.tasks.clear()
        tasks.forEach { this.tasks[it.id] = it }
    }

    override fun save(task: PersistedChapterDownloadTask) = synchronized(lock) {
        tasks[task.id] = task
    }

    override fun delete(taskId: String) {
        synchronized(lock) {
            tasks.remove(taskId)
        }
    }

    override fun clear() {
        synchronized(lock) {
            tasks.clear()
        }
    }
}

class FileChapterDownloadTaskStore(
    private val file: Path,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
) : ChapterDownloadTaskStore {
    private val lock = Any()
    private val listType = object : TypeToken<List<PersistedChapterDownloadTask>>() {}.type

    override fun loadAll(): List<PersistedChapterDownloadTask> = synchronized(lock) {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            if (text.isBlank()) emptyList()
            else gson.fromJson<List<PersistedChapterDownloadTask>>(text, listType).orEmpty()
        }.getOrDefault(emptyList())
    }

    override fun saveAll(tasks: List<PersistedChapterDownloadTask>) = synchronized(lock) {
        writeAll(tasks)
    }

    override fun save(task: PersistedChapterDownloadTask) = synchronized(lock) {
        val all = loadAll().associateBy { it.id }.toMutableMap()
        all[task.id] = task
        writeAll(all.values.sortedBy { it.createdAtMillis })
    }

    override fun delete(taskId: String) = synchronized(lock) {
        writeAll(loadAll().filterNot { it.id == taskId })
    }

    override fun clear() = synchronized(lock) {
        writeAll(emptyList())
    }

    private fun writeAll(tasks: List<PersistedChapterDownloadTask>) {
        val parent = file.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath()
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "jmx-tasks-", ".json")
        try {
            Files.writeString(temp, gson.toJson(tasks), StandardCharsets.UTF_8)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: UnsupportedOperationException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw t
        }
    }
}

fun ChapterDownloadTaskSnapshot.toPersisted(
    completedImageFileNames: List<String> = emptyList()
): PersistedChapterDownloadTask {
    return PersistedChapterDownloadTask(
        id = id,
        albumId = spec.albumId,
        chapterId = spec.chapterId,
        shunt = spec.shunt,
        outputDirectory = spec.outputDirectory.toString(),
        maxImages = spec.maxImages,
        maxImageBytes = spec.maxImageBytes,
        state = state.name,
        progressTotal = progress?.total,
        progressCompleted = progress?.completed,
        progressFailed = progress?.failed,
        progressBytesWritten = progress?.bytesWritten,
        errorMessage = error?.message,
        completedImageFileNames = completedImageFileNames,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

fun PersistedChapterDownloadTask.toSnapshot(): ChapterDownloadTaskSnapshot {
    val path = Path.of(outputDirectory)
    val stateEnum = runCatching { ChapterDownloadTaskState.valueOf(state) }
        .getOrDefault(ChapterDownloadTaskState.Pending)

    val normalized = when (stateEnum) {
        ChapterDownloadTaskState.Running -> ChapterDownloadTaskState.Pending
        else -> stateEnum
    }
    return ChapterDownloadTaskSnapshot(
        id = id,
        spec = ChapterDownloadTaskSpec(
            albumId = albumId,
            chapterId = chapterId,
            shunt = shunt,
            outputDirectory = path,
            maxImages = maxImages,
            maxImageBytes = maxImageBytes
        ),
        state = normalized,
        progress = if (progressTotal != null) {
            ChapterDownloadProgress(
                total = progressTotal,
                completed = progressCompleted ?: 0,
                failed = progressFailed ?: 0,
                bytesWritten = progressBytesWritten ?: 0L
            )
        } else {
            null
        },
        error = errorMessage?.let { JmxError.Unknown(it) },
        report = null,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        completedImageFileNames = completedImageFileNames
    )
}
