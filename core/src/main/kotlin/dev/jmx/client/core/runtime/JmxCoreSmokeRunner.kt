package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.api.LoginSession
import dev.jmx.client.core.chapter.ChapterImageTransferOptions
import dev.jmx.client.core.chapter.ChapterImageTransferReport
import dev.jmx.client.core.chapter.ChapterImageTransferRunner
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.image.ImageOutputStore
import dev.jmx.client.core.image.ImageRestoreBatchRunner
import dev.jmx.client.core.image.ImageRestoreExecutor
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.ImageStoreBatchRunner
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

data class JmxCoreSmokeScenario(
    val username: String,
    val password: String,
    val albumId: String,
    val chapterId: String,
    val shunt: String,
    val imageRowCodec: ImageRowCodec,
    val imageOutputStore: ImageOutputStore,
    val initialize: Boolean = true,
    val imageHeaders: Map<String, String> = emptyMap(),
    val acceptedImageContentTypes: Set<String> = setOf("image/*"),
    val maxImageBytes: Long? = null
)

data class JmxCoreSmokeStep<T>(
    val name: String,
    val result: JmxResult<T>? = null,
    val skippedReason: String? = null
) {
    val isSuccessful: Boolean = result is JmxResult.Success
    val isSkipped: Boolean = skippedReason != null

    fun valueOrNull(): T? {
        return when (result) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure,
            null -> null
        }
    }

    companion object {
        fun <T> success(name: String, value: T): JmxCoreSmokeStep<T> {
            return JmxCoreSmokeStep(name = name, result = JmxResult.Success(value))
        }

        fun <T> failure(name: String, error: JmxError): JmxCoreSmokeStep<T> {
            return JmxCoreSmokeStep(name = name, result = JmxResult.Failure(error))
        }

        fun <T> skipped(name: String, reason: String): JmxCoreSmokeStep<T> {
            return JmxCoreSmokeStep(name = name, skippedReason = reason)
        }
    }
}

data class JmxCoreSmokeReport(
    val initialization: JmxCoreSmokeStep<JmxCoreInitResult>,
    val login: JmxCoreSmokeStep<LoginSession>,
    val albumDetail: JmxCoreSmokeStep<AlbumDetail>,
    val chapterTemplate: JmxCoreSmokeStep<ChapterTemplate>,
    val imageTransfer: JmxCoreSmokeStep<ChapterImageTransferReport>,
    val health: JmxCoreHealth
) {
    val isSuccessful: Boolean =
        initialization.isSuccessful &&
            initialization.valueOrNull()?.isFullySuccessful == true &&
            login.isSuccessful &&
            albumDetail.isSuccessful &&
            chapterTemplate.isSuccessful &&
            imageTransfer.isSuccessful &&
            imageTransfer.valueOrNull()?.failedCount == 0
}

class JmxCoreSmokeRunner(
    private val core: JmxCore
) {
    suspend fun run(scenario: JmxCoreSmokeScenario): JmxCoreSmokeReport {
        val initialization = if (scenario.initialize) {
            runStep("initialize") { core.initializer.initialize() }
        } else {
            JmxCoreSmokeStep.success(
                name = "initialize",
                value = JmxCoreInitResult(
                    domainRefresh = InitStepResult.Success(emptyList()),
                    settingFetch = InitStepResult.Success(
                        dev.jmx.client.core.api.RemoteSetting(
                            apiVersion = core.apiVersionProvider.current(),
                            imageHost = null,
                            shunts = emptyList()
                        )
                    )
                )
            )
        }
        val login = runResultStep("login") {
            core.userApi.login(scenario.username, scenario.password)
        }
        val albumDetail = runResultStep("album_detail") {
            core.albumApi.detailFull(scenario.albumId)
        }
        val chapterTemplate = runResultStep("chapter_template") {
            core.chapterApi.template(
                chapterId = scenario.chapterId,
                shunt = scenario.shunt
            )
        }
        val imageTransfer = when (val template = chapterTemplate.valueOrNull()) {
            null -> JmxCoreSmokeStep.skipped("image_transfer", "chapter_template failed")
            else -> runStep("image_transfer") {
                createImageTransferRunner(scenario).transfer(
                    template = template,
                    options = ChapterImageTransferOptions(
                        headers = scenario.imageHeaders,
                        acceptedContentTypes = scenario.acceptedImageContentTypes,
                        maxBytes = scenario.maxImageBytes
                    )
                )
            }
        }
        return JmxCoreSmokeReport(
            initialization = initialization,
            login = login,
            albumDetail = albumDetail,
            chapterTemplate = chapterTemplate,
            imageTransfer = imageTransfer,
            health = core.healthSnapshot()
        )
    }

    private fun createImageTransferRunner(scenario: JmxCoreSmokeScenario): ChapterImageTransferRunner {
        val restoreRunner = ImageRestoreBatchRunner(
            executor = ImageRestoreExecutor(core.downloader, scenario.imageRowCodec),
            maxConcurrency = core.downloadBatchRunner.maxConcurrency
        )
        val storeRunner = ImageStoreBatchRunner(scenario.imageOutputStore)
        return ChapterImageTransferRunner(restoreRunner, storeRunner)
    }

    private suspend fun <T> runStep(
        name: String,
        block: suspend () -> T
    ): JmxCoreSmokeStep<T> {
        return runCatching { block() }.fold(
            onSuccess = { JmxCoreSmokeStep.success(name, it) },
            onFailure = { JmxCoreSmokeStep.failure(name, JmxError.Unknown(it.message ?: "自检步骤异常", it)) }
        )
    }

    private suspend fun <T> runResultStep(
        name: String,
        block: suspend () -> JmxResult<T>
    ): JmxCoreSmokeStep<T> {
        return runCatching { block() }.fold(
            onSuccess = { JmxCoreSmokeStep(name = name, result = it) },
            onFailure = { JmxCoreSmokeStep.failure(name, JmxError.Unknown(it.message ?: "自检步骤异常", it)) }
        )
    }
}
