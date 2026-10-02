package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.VideoWork
import app.prismia.video.VideoAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Small persistence boundary. A Room implementation can replace this later. */
interface VideoContentStore {
    suspend fun read(key: ContentKey): VideoWork?
    suspend fun write(work: VideoWork)
    suspend fun tombstone(key: ContentKey, reason: String? = null)
    suspend fun isTombstoned(key: ContentKey): Boolean
}

class InMemoryVideoContentStore : VideoContentStore {
    private val lock = Any()
    private val records = linkedMapOf<ContentKey, VideoWork>()
    private val tombstones = linkedMapOf<ContentKey, String?>()

    override suspend fun read(key: ContentKey): VideoWork? = synchronized(lock) { records[key] }

    override suspend fun write(work: VideoWork) {
        synchronized(lock) {
            records[work.key] = work
            tombstones.remove(work.key)
        }
    }

    override suspend fun tombstone(key: ContentKey, reason: String?) {
        synchronized(lock) {
            records.remove(key)
            tombstones[key] = reason
        }
    }

    override suspend fun isTombstoned(key: ContentKey): Boolean = synchronized(lock) { key in tombstones }
}

/**
 * Durable store used until the database module is introduced. It keeps all
 * state in memory after the first read, and commits each mutation through a
 * same-directory atomic replacement so a process or power failure cannot
 * leave a half-written snapshot.
 */
class FileVideoContentStore(
    private val file: File,
) : VideoContentStore {
    private val lock = ReentrantLock()
    private val records = linkedMapOf<ContentKey, VideoWork>()
    private val tombstones = linkedMapOf<ContentKey, String?>()
    private var loaded = false

    override suspend fun read(key: ContentKey): VideoWork? = onDisk { records[key] }

    override suspend fun write(work: VideoWork) = onDisk {
        val previous = snapshot()
        records[work.key] = work.normalizedForStore()
        tombstones.remove(work.key)
        commitOrRestore(previous)
    }

    override suspend fun tombstone(key: ContentKey, reason: String?) = onDisk {
        val previous = snapshot()
        records.remove(key)
        tombstones[key] = reason?.takeIf(String::isNotBlank)
        commitOrRestore(previous)
    }

    override suspend fun isTombstoned(key: ContentKey): Boolean = onDisk { key in tombstones }

    private suspend fun <T> onDisk(action: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            action()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        if (file.isFile) {
            val bytes = FileInputStream(file).use { it.readBytes() }
            val snapshot = try {
                VideoContentStoreCodec.decode(bytes)
            } catch (failure: RuntimeException) {
                throw VideoContentStoreException("video content store is corrupt: ${file.name}", failure)
            }
            records.putAll(snapshot.records)
            tombstones.putAll(snapshot.tombstones)
        }
        loaded = true
    }

    private fun commitOrRestore(previous: StoreSnapshot) {
        try {
            persistLocked()
        } catch (failure: Exception) {
            records.clear()
            records.putAll(previous.records)
            tombstones.clear()
            tombstones.putAll(previous.tombstones)
            throw VideoContentStoreException("unable to persist video content store", failure)
        }
    }

    private fun persistLocked() {
        val parent = file.absoluteFile.parentFile ?: throw IllegalStateException("store has no parent")
        check(parent.exists() || parent.mkdirs()) { "unable to create ${parent.path}" }
        val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(VideoContentStoreCodec.encode(records, tombstones))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun snapshot() = StoreSnapshot(LinkedHashMap(records), LinkedHashMap(tombstones))

    private data class StoreSnapshot(
        val records: LinkedHashMap<ContentKey, VideoWork>,
        val tombstones: LinkedHashMap<ContentKey, String?>,
    )
}

private data class VideoContentStoreSnapshot(
    val records: LinkedHashMap<ContentKey, VideoWork>,
    val tombstones: LinkedHashMap<ContentKey, String?>,
)

/** Bounded binary snapshot codec for [FileVideoContentStore]. */
private object VideoContentStoreCodec {
    private const val MAGIC = 0x50564331 // "PVC1"
    private const val VERSION = 1
    private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
    private const val MAX_RECORDS = 100_000
    private const val MAX_TAGS = 256
    private val MAX_LINKS = ContentSource.entries.size
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(
        records: Map<ContentKey, VideoWork>,
        tombstones: Map<ContentKey, String?>,
    ): ByteArray {
        require(records.size <= MAX_RECORDS && tombstones.size <= MAX_RECORDS) { "too many video records" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(VERSION)
            data.writeInt(records.size)
            records.forEach { (key, work) -> writeWork(data, key, work) }
            data.writeInt(tombstones.size)
            tombstones.forEach { (key, reason) ->
                writeKey(data, key)
                writeNullableString(data, reason)
            }
        }
        return output.toByteArray().also { require(it.size <= MAX_SNAPSHOT_BYTES) { "video store is too large" } }
    }

    fun decode(bytes: ByteArray): VideoContentStoreSnapshot {
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "video store is too large" }
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != MAGIC) throw IllegalArgumentException("invalid video store magic")
                if (data.readUnsignedByte() != VERSION) throw IllegalArgumentException("unsupported video store version")
                val records = readCount(data)
                val decodedRecords = LinkedHashMap<ContentKey, VideoWork>(records)
                repeat(records) {
                    val (key, work) = readWork(data)
                    if (decodedRecords.put(key, work) != null) throw IllegalArgumentException("duplicate video record")
                }
                val tombstoneCount = readCount(data)
                val decodedTombstones = LinkedHashMap<ContentKey, String?>(tombstoneCount)
                repeat(tombstoneCount) {
                    val key = readKey(data)
                    if (decodedTombstones.put(key, readNullableString(data)) != null) {
                        throw IllegalArgumentException("duplicate video tombstone")
                    }
                }
                if (decodedRecords.keys.any { it in decodedTombstones }) {
                    throw IllegalArgumentException("record and tombstone overlap")
                }
                if (data.available() != 0) throw IllegalArgumentException("trailing video store bytes")
                return VideoContentStoreSnapshot(decodedRecords, decodedTombstones)
            }
        } catch (failure: EOFException) {
            throw IllegalArgumentException("truncated video store", failure)
        }
    }

    private fun writeWork(data: DataOutputStream, key: ContentKey, work: VideoWork) {
        require(work.key == key) { "record key mismatch" }
        writeKey(data, key)
        writeString(data, work.title)
        writeNullableString(data, work.author)
        writeNullableString(data, work.coverUrl)
        writeNullableLong(data, work.durationMs)
        writeNullableString(data, work.description)
        require(work.tags.size <= MAX_TAGS) { "too many video tags" }
        data.writeInt(work.tags.size)
        work.tags.forEach { writeString(data, it) }
        data.writeUTF(work.availability.name)
        require(work.sourceLinks.size <= MAX_LINKS) { "too many source links" }
        data.writeInt(work.sourceLinks.size)
        work.sourceLinks.forEach { (source, link) -> data.writeUTF(source.name); writeString(data, link) }
    }

    private fun readWork(data: DataInputStream): Pair<ContentKey, VideoWork> {
        val key = readKey(data)
        val title = readString(data)
        val author = readNullableString(data)
        val coverUrl = readNullableString(data)
        val durationMs = readNullableLong(data)
        val description = readNullableString(data)
        val tags = readCount(data, MAX_TAGS).let { count -> List(count) { readString(data) } }
        val availability = readAvailability(data)
        val sourceLinkCount = readCount(data, MAX_LINKS)
        val links = linkedMapOf<ContentSource, String>()
        repeat(sourceLinkCount) {
            val source = readSource(data)
            if (links.put(source, readString(data)) != null) throw IllegalArgumentException("duplicate source link")
        }
        return key to VideoWork(
            key = key,
            title = title,
            author = author,
            coverUrl = coverUrl,
            durationMs = durationMs,
            description = description,
            tags = tags,
            availability = availability,
            sourceLinks = links,
        )
    }

    private fun writeKey(data: DataOutputStream, key: ContentKey) {
        data.writeUTF(key.contentType.name)
        data.writeUTF(key.source.name)
        writeString(data, key.remoteId)
    }

    private fun readKey(data: DataInputStream): ContentKey = ContentKey(
        contentType = runCatching { ContentType.valueOf(data.readUTF()) }.getOrElse { throw IllegalArgumentException("invalid content type", it) },
        source = readSource(data),
        remoteId = readString(data),
    )

    private fun readSource(data: DataInputStream): ContentSource =
        runCatching { ContentSource.valueOf(data.readUTF()) }.getOrElse { throw IllegalArgumentException("invalid content source", it) }

    private fun readAvailability(data: DataInputStream): VideoAvailability =
        runCatching { VideoAvailability.valueOf(data.readUTF()) }.getOrElse { throw IllegalArgumentException("invalid availability", it) }

    private fun readCount(data: DataInputStream, maximum: Int = MAX_RECORDS): Int {
        val count = data.readInt()
        if (count !in 0..maximum) throw IllegalArgumentException("invalid record count")
        return count
    }

    private fun writeString(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "video store string is too large" }
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun writeNullableString(data: DataOutputStream, value: String?) {
        if (value == null) data.writeInt(-1) else writeString(data, value)
    }

    private fun readString(data: DataInputStream): String = readStringAt(data, MAX_STRING_BYTES)

    private fun readStringAt(data: DataInputStream, maximum: Int): String {
        val length = data.readInt()
        if (length < 0 || length > maximum || length > data.available()) throw IllegalArgumentException("invalid string length")
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readNullableString(data: DataInputStream): String? {
        val length = data.readInt()
        if (length == -1) return null
        if (length < 0 || length > MAX_STRING_BYTES || length > data.available()) throw IllegalArgumentException("invalid nullable string length")
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeNullableLong(data: DataOutputStream, value: Long?) {
        data.writeBoolean(value != null)
        if (value != null) data.writeLong(value)
    }

    private fun readNullableLong(data: DataInputStream): Long? = if (data.readBoolean()) data.readLong() else null

}

private fun VideoWork.normalizedForStore() = copy(tags = tags.toList(), sourceLinks = sourceLinks.toMap())

class VideoContentStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)\n