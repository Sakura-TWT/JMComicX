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

    val imageHeaders: Map<String, String>? = null,
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

    fun errorOrNull(): JmxError? {
        return when (result) {
            is JmxResult.Failure -> result.error
            is JmxResult.Success,
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

    val issues: List<JmxDiagnosticIssue> = buildList {
        listOf(initialization, login, albumDetail, chapterTemplate, imageTransfer).forEach { step ->
            step.toIssueOrNull()?.let(::add)
        }
        initialization.valueOrNull()?.toDiagnosticIssues()?.let(::addAll)
        imageTransfer.valueOrNull()?.toDiagnosticIssues("image_transfer")?.let(::addAll)
    }

    val failedSteps: List<String> = issues
        .filter { it.severity != JmxDiagnosticSeverity.Info }
        .map { it.step }

    val skippedSteps: List<String> = issues
        .filter { it.severity == JmxDiagnosticSeverity.Info }
        .map { it.step }
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

private fun JmxCoreSmokeStep<*>.toIssueOrNull(): JmxDiagnosticIssue? {
    errorOrNull()?.let { error ->
        return JmxDiagnosticIssue(
            step = name,
            severity = error.diagnosticSeverity(),
            message = error.message,
            error = error
        )
    }
    return skippedReason?.let {
        JmxDiagnosticIssue(
            step = name,
            severity = JmxDiagnosticSeverity.Info,
            message = it
        )
    }
}

private fun JmxCoreInitResult.toDiagnosticIssues(): List<JmxDiagnosticIssue> {
    return buildList {
        when (val domain = domainRefresh) {
            is InitStepResult.Success -> Unit
            is InitStepResult.Failure -> add(domain.error.toIssue("initialize.domain_refresh"))
        }
        when (val setting = settingFetch) {
            is InitStepResult.Success -> Unit
            is InitStepResult.Failure -> add(setting.error.toIssue("initialize.setting"))
        }
    }
}

private fun ChapterImageTransferReport.toDiagnosticIssues(stepPrefix: String): List<JmxDiagnosticIssue> {
    return buildList {
        restoreResults.forEach { item ->
            when (val result = item.result) {
                is JmxResult.Success -> Unit
                is JmxResult.Failure -> add(result.error.toIssue("$stepPrefix.restore[${item.item.index}]"))
            }
        }
        storeResults.forEach { item ->
            when (val result = item.result) {
                is JmxResult.Success -> Unit
                is JmxResult.Failure -> add(result.error.toIssue("$stepPrefix.store[${item.item.index}]"))
            }
        }
    }
}

private fun JmxError.toIssue(step: String): JmxDiagnosticIssue {
    return JmxDiagnosticIssue(
        step = step,
        severity = diagnosticSeverity(),
        message = message,
        error = this
    )
}
