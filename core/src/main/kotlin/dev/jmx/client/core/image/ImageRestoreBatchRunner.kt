package dev.jmx.client.core.image

import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ImageRestoreBatchItem(
    val index: Int,
    val request: ImageDownloadRequest
)

data class ImageRestoreBatchResult(
    val item: ImageRestoreBatchItem,
    val result: JmxResult<ImageRestoreResult>
)

class ImageRestoreBatchRunner(
    private val executor: ImageRestoreExecutor,
    val maxConcurrency: Int = DEFAULT_CONCURRENCY
) {
    suspend fun downloadAndRestore(
        requests: List<ImageDownloadRequest>
    ): List<ImageRestoreBatchResult> {
        val items = requests.mapIndexed { index, request -> ImageRestoreBatchItem(index, request) }
        return downloadAndRestoreItems(items)
    }

    suspend fun downloadAndRestoreItems(
        items: List<ImageRestoreBatchItem>
    ): List<ImageRestoreBatchResult> {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        ImageRestoreBatchResult(
                            item = item,
                            result = executor.downloadAndRestore(item.request)
                        )
                    }
                }
            }.awaitAll()
                .sortedBy { it.item.index }
        }
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 4
    }
}
