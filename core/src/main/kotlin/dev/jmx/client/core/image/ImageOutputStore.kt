package dev.jmx.client.core.image

import dev.jmx.client.core.result.JmxResult

data class ImageOutputKey(
    val index: Int,
    val filename: String,
    val cacheKey: String,
    val displayFilename: String = filename,
    val extension: String? = null
)

data class ImageOutputRecord(
    val key: ImageOutputKey,
    val sourceUrl: String,
    val contentType: String?,
    val byteCount: Int,
    val restored: Boolean
)

interface ImageOutputStore {
    fun put(key: ImageOutputKey, result: ImageRestoreResult): JmxResult<ImageOutputRecord>
}

class InMemoryImageOutputStore : ImageOutputStore {
    private val entries = linkedMapOf<ImageOutputKey, StoredImage>()

    override fun put(key: ImageOutputKey, result: ImageRestoreResult): JmxResult<ImageOutputRecord> {
        entries[key] = StoredImage(
            bytes = result.bytes.copyOf(),
            contentType = result.contentType,
            sourceUrl = result.plan.sourceUrl,
            restored = result.restored
        )
        return JmxResult.Success(
            ImageOutputRecord(
                key = key,
                sourceUrl = result.plan.sourceUrl,
                contentType = result.contentType,
                byteCount = result.bytes.size,
                restored = result.restored
            )
        )
    }

    fun bytes(key: ImageOutputKey): ByteArray? = entries[key]?.bytes?.copyOf()

    fun records(): List<ImageOutputRecord> {
        return entries.map { (key, value) ->
            ImageOutputRecord(
                key = key,
                sourceUrl = value.sourceUrl,
                contentType = value.contentType,
                byteCount = value.bytes.size,
                restored = value.restored
            )
        }
    }

    private class StoredImage(
        val bytes: ByteArray,
        val contentType: String?,
        val sourceUrl: String,
        val restored: Boolean
    )
}

data class ImageStoreBatchItem(
    val index: Int,
    val result: ImageRestoreResult
) {
    val outputKey: ImageOutputKey = ImageOutputKey(
        index = index,
        filename = result.plan.filename,
        cacheKey = result.plan.cacheKey,
        displayFilename = result.plan.displayFilename,
        extension = result.plan.extension
    )
}

data class ImageStoreBatchResult(
    val item: ImageStoreBatchItem,
    val result: JmxResult<ImageOutputRecord>
)

class ImageStoreBatchRunner(
    private val store: ImageOutputStore
) {
    fun save(results: List<ImageRestoreBatchResult>): List<ImageStoreBatchResult> {
        return results.mapNotNull { batchResult ->
            when (val restoreResult = batchResult.result) {
                is JmxResult.Success -> {
                    val item = ImageStoreBatchItem(batchResult.item.index, restoreResult.value)
                    ImageStoreBatchResult(item, store.put(item.outputKey, restoreResult.value))
                }
                is JmxResult.Failure -> null
            }
        }
    }
}
