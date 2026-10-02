package dev.jmx.client

import org.json.JSONObject
import java.io.File

internal data class OfflineStagedPage(val page: OfflinePage, val file: File)

/** Small, durable receipts for the at-most-four pages outside the published prefix.
 * Never infer completion from an orphan image: a receipt must match the template and SHA-256.
 * A receipt also survives image rename followed by a failed metadata write.
 */
internal class OfflinePageStaging(
    private val root: File,
    private val albumId: String,
    private val chapterId: String,
    private val fingerprint: String,
) {
    fun target(index: Int): File = OfflineFiles.resolve(root, relative(index, "png"))

    fun find(index: Int): OfflineStagedPage? {
        val receipt = receipt(index)
        if (!receipt.isFile || receipt.length() !in 1..4096) return null
        return runCatching {
            val json = JSONObject(receipt.readText())
            if (json.getString("fingerprint") != fingerprint) return null
            val path = json.getString("path")
            val extension = path.substringAfterLast('.')
            require(path == relative(index, extension))
            val final = OfflineFiles.resolve(root, path)
            val page = OfflinePage(index, path, json.getLong("bytes"), json.getString("sha256"))
            val file = payload(index).takeIf { it.isFile } ?: final
            if (page.byteCount <= 0 || !file.isFile || file.length() != page.byteCount ||
                OfflineFiles.digest(file) != page.sha256) return null
            OfflineStagedPage(page, file)
        }.getOrNull()
    }

    fun save(index: Int, file: File, extension: String): OfflineStagedPage {
        val path = relative(index, extension)
        OfflineFiles.resolve(root, path) // Validate extension and ownership before writing any receipt.
        val page = OfflinePage(index, path, file.length(), OfflineFiles.digest(file))
        check(page.byteCount > 0) { "离线页面为空" }
        val destination = payload(index)
        OfflineFiles.atomicMove(file, destination)
        OfflineFiles.atomicWrite(receipt(index), JSONObject()
            .put("fingerprint", fingerprint).put("path", path)
            .put("bytes", page.byteCount).put("sha256", page.sha256).toString())
        return OfflineStagedPage(page, destination)
    }

    /** Called under the manager's short file lease, immediately before metadata publication. */
    fun promote(staged: OfflineStagedPage): File {
        val final = OfflineFiles.resolve(root, staged.page.relativePath)
        if (staged.file != final) OfflineFiles.atomicMove(staged.file, final)
        return final
    }

    /** Only after metadata is durable. Retained receipts otherwise allow a retry with no network. */
    fun committed(index: Int) {
        receipt(index).delete()
        payload(index).delete()
    }

    fun cleanupIncomplete(index: Int) {
        val target = target(index)
        File(target.parentFile, target.name + ".source.part").delete()
        File(target.parentFile, target.name + ".part").delete()
        File(receipt(index).path + ".tmp").delete()
    }

    private fun relative(index: Int, extension: String): String {
        require(index in 0 until 100_000)
        return "$albumId/$chapterId/${index.toString().padStart(6, '0')}.$extension"
    }
    private fun receipt(index: Int) = File(target(index).path + ".ready.json")
    private fun payload(index: Int) = File(target(index).path + ".ready")
}
