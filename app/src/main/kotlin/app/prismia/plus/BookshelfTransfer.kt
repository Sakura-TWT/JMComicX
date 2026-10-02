package app.prismia.plus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface BookshelfExportResult {
    /** [location] 是给用户看的落盘位置描述，不是可解析的路径。 */
    data class Success(
        val fileName: String,
        val location: String,
        val entryCount: Int,
        val groupCount: Int,
    ) : BookshelfExportResult

    data class Failure(val message: String) : BookshelfExportResult
}

internal sealed interface BookshelfImportResult {
    data class Success(val snapshot: BookshelfSnapshot) : BookshelfImportResult

    data class Failure(val message: String) : BookshelfImportResult
}

/**
 * 把书架快照写成一份固定格式的文件放进"下载"目录。
 *
 * 走 MediaStore 而不是直接写 [Environment.getExternalStoragePublicDirectory]：minSdk 33 下
 * 后者已经没有写权限，而 MediaStore 的 Downloads 集合对自己插入的文件不需要任何权限。
 * 极少数 ROM 上 insert 会失败（分区存储被魔改过），这时退回应用自己的外部目录，
 * 至少让用户能通过文件管理器拿到文件，而不是只得到一句"导出失败"。
 */
internal suspend fun exportBookshelfSnapshot(
    context: Context,
    snapshot: BookshelfSnapshot,
    exportedAt: Long,
): BookshelfExportResult = withContext(Dispatchers.IO) {
    if (snapshot.entries.isEmpty() && snapshot.groups.isEmpty()) {
        return@withContext BookshelfExportResult.Failure("所选范围里没有内容可以导出。")
    }
    val fileName = bookshelfExportFileName(exportedAt)
    val payload = encodeBookshelfSnapshot(snapshot, exportedAt).toByteArray(Charsets.UTF_8)
    val success = BookshelfExportResult.Success(
        fileName = fileName,
        location = "下载",
        entryCount = snapshot.entries.size,
        groupCount = snapshot.groups.size,
    )
    val mediaStoreUri = runCatching { insertDownload(context, fileName, payload) }.getOrNull()
    if (mediaStoreUri != null) return@withContext success

    val fallback = runCatching { writeAppExternalDownload(context, fileName, payload) }.getOrNull()
        ?: return@withContext BookshelfExportResult.Failure("写入下载目录失败，请检查存储空间后重试。")
    success.copy(location = fallback.parent ?: fallback.absolutePath)
}

/** 读取用户选中的文件并解析成快照，失败时给出能指导下一步的提示。 */
internal suspend fun importBookshelfSnapshot(
    context: Context,
    uri: Uri,
): BookshelfImportResult = withContext(Dispatchers.IO) {
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // 上限只是防止误选了几百 MB 的文件把内存读爆，书架文件实际只有几十 KB。
            String(stream.readNBytes(MAX_BOOKSHELF_FILE_BYTES), Charsets.UTF_8)
        }
    }.getOrNull() ?: return@withContext BookshelfImportResult.Failure("无法读取所选文件，请换一个位置再试。")
    val snapshot = decodeBookshelfSnapshot(text)
        ?: return@withContext BookshelfImportResult.Failure("这不是 JMComicX 导出的书架文件，或文件已损坏。")
    if (snapshot.entries.isEmpty() && snapshot.groups.isEmpty()) {
        return@withContext BookshelfImportResult.Failure("文件里没有可导入的书架内容。")
    }
    BookshelfImportResult.Success(snapshot)
}

internal fun bookshelfExportFileName(exportedAt: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(exportedAt))
    return "JMComicX-bookshelf-$stamp.json"
}

private fun insertDownload(context: Context, fileName: String, payload: ByteArray): Uri? {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, BOOKSHELF_EXPORT_MIME_TYPE)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use { it.write(payload) }
            ?: error("下载目录不可写")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    }.getOrElse { error ->
        // 半成品文件留在下载目录里只会让用户困惑，插入成功但写入失败时先清掉再上报。
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}

private fun writeAppExternalDownload(context: Context, fileName: String, payload: ByteArray): File? {
    val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
    if (!directory.exists() && !directory.mkdirs()) return null
    val file = File(directory, fileName)
    file.writeBytes(payload)
    return file
}

private const val BOOKSHELF_EXPORT_MIME_TYPE = "application/json"
private const val MAX_BOOKSHELF_FILE_BYTES = 8 * 1024 * 1024
