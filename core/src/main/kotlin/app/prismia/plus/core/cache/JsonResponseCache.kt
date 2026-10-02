package app.prismia.plus.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/**
 * 解密后 JSON 响应的磁盘缓存。
 *
 * 为什么缓存解密后的 JSON、而不是在 HTTP 层加 OkHttp Cache：接口响应是 AES-ECB 加密的，
 * 密钥由请求当时的 Tokenparam 时间戳派生（见 ApiResponseDecoder）。HTTP 缓存回放的是密文，
 * 换个时间戳就解不开；缓存明文 JSON 则与时间戳无关。
 *
 * 存储形式是"一键一文件"，而不是塞进 [FileKeyValueStore]：后者是 .properties，
 * 每次写入都要整体重写并转义，首页 promote 这种上百 KB 的响应会把它拖死。
 *
 * @param directory 缓存目录，不存在时首次写入创建。
 * @param maxBytes 缓存总量上限，超出时按最后修改时间从旧到新裁剪。
 */
class JsonResponseCache(
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * @param generation 写入时的缓存代号（见 [invalidateOtherGenerations]）。
     */
    data class Entry(
        val json: String,
        val storedAtMillis: Long,
        val generation: String,
    ) {
        fun ageMillis(nowMillis: Long): Long = (nowMillis - storedAtMillis).coerceAtLeast(0L)
    }

    private val locks = ConcurrentHashMap<String, Any>()

    fun read(key: String): Entry? = withKeyLock(key) {
        val file = fileFor(key)
        if (!Files.exists(file)) return@withKeyLock null
        runCatching {
            val text = Files.readString(file)
            val separator = text.indexOf('\n')
            if (separator <= 0) return@runCatching null
            val header = text.substring(0, separator).split(HEADER_SEPARATOR)
            val storedAt = header.getOrNull(0)?.toLongOrNull() ?: return@runCatching null
            Entry(
                json = text.substring(separator + 1),
                storedAtMillis = storedAt,
                generation = header.getOrNull(1).orEmpty(),
            )
        }.getOrNull()
    }

    fun write(key: String, json: String, generation: String = "") {
        withKeyLock(key) {
            runCatching {
                Files.createDirectories(directory)
                val file = fileFor(key)
                val temp = Files.createTempFile(directory, file.fileName.toString(), ".tmp")
                try {
                    Files.writeString(temp, "${nowMillis()}$HEADER_SEPARATOR$generation\n$json")
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                } catch (failure: Throwable) {
                    Files.deleteIfExists(temp)
                    throw failure
                }
            }
        }
        pruneIfNeeded()
    }

    fun remove(key: String) {
        withKeyLock(key) { runCatching { Files.deleteIfExists(fileFor(key)) } }
    }

    fun clear() {
        runCatching {
            entryFiles().forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * 丢弃所有非 [generation] 代号的条目。
     *
     * 官方客户端在 setting 里下发 `ad_cache_version` 之类的版本号，客户端据此让本地缓存整体失效，
     * 不用发版也不用等 TTL 自然过期。这里给同一机制留出入口：把服务端下发的版本号作为代号，
     * 版本号一变，旧内容立刻不再被采用。
     */
    fun invalidateOtherGenerations(generation: String) {
        runCatching {
            entryFiles().forEach { file ->
                val storedGeneration = runCatching {
                    Files.newBufferedReader(file).use { it.readLine() }
                        ?.split(HEADER_SEPARATOR)
                        ?.getOrNull(1)
                        .orEmpty()
                }.getOrNull() ?: return@forEach
                if (storedGeneration != generation) Files.deleteIfExists(file)
            }
        }
    }

    private fun pruneIfNeeded() {
        runCatching {
            val files = entryFiles()
            var total = files.sumOf { runCatching { it.fileSize() }.getOrDefault(0L) }
            if (total <= maxBytes) return@runCatching
            files
                .sortedBy { runCatching { it.getLastModifiedTime().toMillis() }.getOrDefault(0L) }
                .forEach { file ->
                    if (total <= maxBytes) return@forEach
                    val size = runCatching { file.fileSize() }.getOrDefault(0L)
                    if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) total -= size
                }
        }
    }

    private fun entryFiles(): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory, "*$FILE_SUFFIX").use { it.toList() }
    }

    private fun fileFor(key: String): Path = directory.resolve(key.sha256Hex() + FILE_SUFFIX)

    private inline fun <T> withKeyLock(key: String, block: () -> T): T {
        return synchronized(locks.getOrPut(key) { Any() }, block)
    }

    private companion object {
        const val FILE_SUFFIX = ".json"
        const val HEADER_SEPARATOR = "\t"
        const val DEFAULT_MAX_BYTES = 12L * 1024L * 1024L
    }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
