package dev.jmx.client.core.image

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class FileImageOutputRecord(
    val record: ImageOutputRecord,
    val path: Path
)

class FileImageOutputStore(
    private val directory: Path
) : ImageOutputStore {
    private val lock = Any()
    private val paths = linkedMapOf<ImageOutputKey, Path>()

    override fun put(key: ImageOutputKey, result: ImageRestoreResult): JmxResult<ImageOutputRecord> {
        return when (val stored = putFile(key, result)) {
            is JmxResult.Success -> JmxResult.Success(stored.value.record)
            is JmxResult.Failure -> stored
        }
    }

    fun putFile(key: ImageOutputKey, result: ImageRestoreResult): JmxResult<FileImageOutputRecord> {
        return synchronized(lock) {
            runCatching {
                Files.createDirectories(directory)
                val target = directory.resolve(key.toFileName(result.contentType))
                val temp = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
                try {
                    Files.write(temp, result.bytes)
                    moveReplacing(temp, target)
                } catch (failure: Throwable) {
                    Files.deleteIfExists(temp)
                    throw failure
                }
                paths[key] = target
                FileImageOutputRecord(
                    record = ImageOutputRecord(
                        key = key,
                        sourceUrl = result.plan.sourceUrl,
                        contentType = result.contentType,
                        byteCount = result.bytes.size,
                        restored = result.restored
                    ),
                    path = target
                )
            }.fold(
                onSuccess = { JmxResult.Success(it) },
                onFailure = {
                    JmxResult.Failure(JmxError.Schema("图片文件写入失败", field = "image.output", cause = it))
                }
            )
        }
    }

    fun path(key: ImageOutputKey): Path? = synchronized(lock) { paths[key] }

    private fun ImageOutputKey.toFileName(contentType: String?): String {
        val extension = extension?.toSafeExtension()
            ?: extensionFromContentType(contentType)
            ?: "img"
        val name = displayFilename.substringBeforeLast('.', displayFilename).ifBlank { filename }
        val safeName = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "image" }
        val cachePrefix = cacheKey.take(12).replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return "${index.toString().padStart(5, '0')}_${safeName}_$cachePrefix.$extension"
    }

    private fun String.toSafeExtension(): String? {
        val safe = lowercase().replace(Regex("""[^a-z0-9]"""), "")
        return safe.takeIf { it.isNotBlank() && it.length <= 8 }
    }

    private fun extensionFromContentType(contentType: String?): String? {
        return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> null
        }
    }

    private fun moveReplacing(temp: Path, target: Path) {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: UnsupportedOperationException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
