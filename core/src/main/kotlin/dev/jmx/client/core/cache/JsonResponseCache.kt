package dev.jmx.client.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Managed, bounded JSON disk cache. One lazy index scan, not a directory scan on every write. */
class JsonResponseCache(
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val json: String, val storedAtMillis: Long, val generation: String) {
        fun ageMillis(nowMillis: Long): Long = if (nowMillis < storedAtMillis) Long.MAX_VALUE else
            (nowMillis - storedAtMillis).takeIf { it >= 0 } ?: Long.MAX_VALUE
    }
    private data class DiskEntry(val bytes: Long, var touched: Long)
    // Bounded coordination memory regardless of the number of query keys encountered.
    private val stripes = Array(64) { Any() }
    private val indexLock = Any()
    private var index: MutableMap<Path, DiskEntry>? = null
    private var totalBytes = 0L
    internal var indexScanCount = 0
        private set

    fun read(key: String): Entry? = withKeyLock(key) {
        val file = fileFor(key)
        val entry = runCatching {
            if (!Files.exists(file) || Files.size(file) > maxBytes) return@runCatching null
            val text = Files.readString(file)
            val separator = text.indexOf('\n')
            if (separator <= 0) return@runCatching null
            val header = text.substring(0, separator).split(HEADER_SEPARATOR)
            val storedAt = header.getOrNull(0)?.toLongOrNull() ?: return@runCatching null
            Entry(text.substring(separator + 1), storedAt, header.getOrNull(1).orEmpty())
        }.getOrNull()
        if (entry != null) synchronized(indexLock) { index?.get(file)?.touched = nowMillis() }
        entry
    }

    fun write(key: String, json: String, generation: String = "") = withKeyLock(key) {
        val bytes = "${nowMillis()}$HEADER_SEPARATOR$generation\n$json".toByteArray(Charsets.UTF_8)
        // Never create an entry that would evict the entire useful cache and exceed the budget.
        if (bytes.size.toLong() > maxBytes || maxBytes <= 0) return@withKeyLock
        synchronized(indexLock) {
            runCatching {
                val entries = ensureIndex()
                Files.createDirectories(directory)
                val file = fileFor(key)
                val temp = Files.createTempFile(directory, file.fileName.toString(), ".tmp")
                try {
                    Files.write(temp, bytes)
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                    totalBytes -= entries.put(file, DiskEntry(bytes.size.toLong(), nowMillis()))?.bytes ?: 0
                    totalBytes += bytes.size
                    prune(entries)
                } finally { Files.deleteIfExists(temp) }
            }
        }
        Unit
    }

    fun remove(key: String) = withKeyLock(key) {
        synchronized(indexLock) {
            val file = fileFor(key)
            if (runCatching { Files.deleteIfExists(file); true }.getOrDefault(false)) {
                totalBytes -= index?.remove(file)?.bytes ?: 0
            }
        }
        Unit
    }

    fun clear() {
        synchronized(indexLock) {
            runCatching { entryFiles().forEach { Files.deleteIfExists(it) } }
            index = null
            totalBytes = 0
        }
    }

    fun invalidateOtherGenerations(generation: String) {
        synchronized(indexLock) {
            val entries = ensureIndex()
            entries.keys.toList().forEach { file ->
                val stored = runCatching { Files.newBufferedReader(file).use { it.readLine() }?.split(HEADER_SEPARATOR)?.getOrNull(1).orEmpty() }.getOrNull()
                if (stored != null && stored != generation && runCatching { Files.deleteIfExists(file); true }.getOrDefault(false)) {
                    totalBytes -= entries.remove(file)?.bytes ?: 0
                }
            }
        }
    }

    private fun ensureIndex(): MutableMap<Path, DiskEntry> {
        index?.let { return it }
        indexScanCount++
        val entries = mutableMapOf<Path, DiskEntry>()
        entryFiles().forEach { file -> runCatching { entries[file] = DiskEntry(Files.size(file), Files.getLastModifiedTime(file).toMillis()) } }
        totalBytes = entries.values.sumOf { it.bytes }
        index = entries
        return entries
    }

    private fun prune(entries: MutableMap<Path, DiskEntry>) {
        if (totalBytes <= maxBytes) return
        // OS cache reclamation can remove files behind the index; account for those first.
        entries.keys.toList().forEach { file -> if (!Files.exists(file)) totalBytes -= entries.remove(file)?.bytes ?: 0 }
        if (totalBytes <= maxBytes) return
        for ((file, _) in entries.entries.sortedBy { it.value.touched }) {
            if (totalBytes <= maxBytes) break
            if (runCatching { Files.deleteIfExists(file); true }.getOrDefault(false)) totalBytes -= entries.remove(file)?.bytes ?: 0
        }
    }

    private fun entryFiles(): List<Path> = if (!Files.isDirectory(directory)) emptyList() else
        Files.newDirectoryStream(directory, "*$FILE_SUFFIX").use { it.toList() }
    private fun fileFor(key: String): Path = directory.resolve(key.sha256Hex() + FILE_SUFFIX)
    private inline fun <T> withKeyLock(key: String, block: () -> T): T =
        synchronized(stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size], block)

    private companion object {
        const val FILE_SUFFIX = ".json"
        const val HEADER_SEPARATOR = "\t"
        const val DEFAULT_MAX_BYTES = 12L * 1024L * 1024L
    }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return CharArray(digest.size * 2).also { output -> digest.forEachIndexed { i, b ->
        val value = b.toInt() and 255; output[i * 2] = hex[value ushr 4]; output[i * 2 + 1] = hex[value and 15]
    } }.concatToString()
}
