package app.prismia.plus.core.chapter

import app.prismia.plus.core.download.DownloadObserver
import app.prismia.plus.core.image.ImageRestoreBatchResult
import app.prismia.plus.core.image.ImageRestoreBatchRunner
import app.prismia.plus.core.image.ImageStoreBatchResult
import app.prismia.plus.core.image.ImageStoreBatchRunner
import app.prismia.plus.core.result.JmxResult

data class ChapterImageTransferOptions(

    val headers: Map<String, String>? = null,
    val acceptedContentTypes: Set<String> = setOf("image/*"),
    val maxBytes: Long? = null,
    val observerFactory: (index: Int, url: String) -> DownloadObserver = { _, _ -> DownloadObserver.None }
)

data class ChapterImageTransferReport(
    val restoreResults: List<ImageRestoreBatchResult>,
    val storeResults: List<ImageStoreBatchResult>
) {
    val totalCount: Int = restoreResults.size
    val restoredOrOriginalCount: Int = restoreResults.count { it.result is JmxResult.Success }
    val storedCount: Int = storeResults.count { it.result is JmxResult.Success }
    val failedCount: Int = restoreResults.count { it.result is JmxResult.Failure } +
        storeResults.count { it.result is JmxResult.Failure }
}

class ChapterImageTransferRunner(
    private val restoreBatchRunner: ImageRestoreBatchRunner,
    private val storeBatchRunner: ImageStoreBatchRunner
) {
    suspend fun transfer(
        template: ChapterTemplate,
        options: ChapterImageTransferOptions = ChapterImageTransferOptions()
    ): ChapterImageTransferReport {
        val restoreResults = restoreBatchRunner.downloadAndRestore(
            template.toImageDownloadRequests(
                headers = options.headers
                    ?: app.prismia.plus.core.download.ImageHttpHeaders.default(
                        refererHost = template.imageHost
                    ),
                acceptedContentTypes = options.acceptedContentTypes,
                maxBytes = options.maxBytes,
                observerFactory = options.observerFactory
            )
        )
        val storeResults = storeBatchRunner.save(restoreResults)
        return ChapterImageTransferReport(
            restoreResults = restoreResults,
            storeResults = storeResults
        )
    }
}
