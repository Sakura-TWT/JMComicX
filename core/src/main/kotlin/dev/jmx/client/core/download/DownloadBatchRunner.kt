package dev.jmx.client.core.download

import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DownloadBatchRunner(
    private val downloader: Downloader,
    val maxConcurrency: Int = DEFAULT_CONCURRENCY
) {
    suspend fun download(
        requests: List<DownloadRequest>,
        sinkFactory: (DownloadBatchItem) -> ByteSink
    ): List<DownloadBatchResult> {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        return coroutineScope {
            requests.mapIndexed { index, request ->
                val item = DownloadBatchItem(index, request)
                async {
                    semaphore.withPermit {
                        val result = downloader.download(request, sinkFactory(item))
                        DownloadBatchResult(item, result)
                    }
                }
            }.awaitAll()
                .sortedBy { it.item.index }
        }
    }

    suspend fun downloadItems(
        items: List<DownloadBatchItem>,
        sinkFactory: (DownloadBatchItem) -> ByteSink
    ): List<DownloadBatchResult> {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        val result = downloader.download(item.request, sinkFactory(item))
                        DownloadBatchResult(item, result)
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
