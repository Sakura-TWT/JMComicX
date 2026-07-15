package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.chapter.ChapterImageTransferOptions
import dev.jmx.client.core.chapter.ChapterImageTransferReport
import dev.jmx.client.core.chapter.ChapterImageTransferRunner
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.image.FileImageOutputStore
import dev.jmx.client.core.image.ImageIoRowCodec
import dev.jmx.client.core.image.ImageRestoreBatchRunner
import dev.jmx.client.core.image.ImageRestoreExecutor
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.ImageStoreBatchRunner
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import java.nio.file.Path

data class JmxLiveReadingScenario(
    val albumId: String = DEFAULT_ALBUM_ID,
    val chapterId: String = DEFAULT_CHAPTER_ID,
    val shunt: String = "1",
    val maxImages: Int = 2,
    val initialize: Boolean = true,
    val outputDirectory: Path,
    val imageRowCodec: ImageRowCodec = ImageIoRowCodec(),
    val maxImageBytes: Long? = 15L * 1024L * 1024L
) {
    companion object {
        const val DEFAULT_ALBUM_ID = JmxLiveProbeDefaults.SAMPLE_ALBUM_ID
        const val DEFAULT_CHAPTER_ID = JmxLiveProbeDefaults.SAMPLE_CHAPTER_ID
    }
}

data class JmxLiveReadingStep<T>(
    val name: String,
    val result: JmxResult<T>? = null,
    val skippedReason: String? = null
) {
    val isSuccessful: Boolean = result is JmxResult.Success
    val isSkipped: Boolean = skippedReason != null
    fun valueOrNull(): T? = (result as? JmxResult.Success)?.value
    fun errorOrNull(): JmxError? = (result as? JmxResult.Failure)?.error

    companion object {
        fun <T> success(name: String, value: T): JmxLiveReadingStep<T> =
            JmxLiveReadingStep(name = name, result = JmxResult.Success(value))

        fun <T> failure(name: String, error: JmxError): JmxLiveReadingStep<T> =
            JmxLiveReadingStep(name = name, result = JmxResult.Failure(error))

        fun <T> skipped(name: String, reason: String): JmxLiveReadingStep<T> =
            JmxLiveReadingStep(name = name, skippedReason = reason)
    }
}

data class JmxLiveReadingAcceptance(
    val initOk: Boolean,
    val albumOk: Boolean,
    val templateOk: Boolean,
    val imageTransferOk: Boolean,
    val restoredOrOriginalOk: Boolean
) {
    val meetsMinimum: Boolean =
        albumOk && templateOk && imageTransferOk && restoredOrOriginalOk

    val passedCount: Int = listOf(initOk, albumOk, templateOk, imageTransferOk, restoredOrOriginalOk)
        .count { it }
    val totalCount: Int = 5
}

data class JmxLiveReadingReport(
    val scenario: JmxLiveReadingScenario,
    val initialization: JmxLiveReadingStep<JmxCoreInitResult>,
    val albumDetail: JmxLiveReadingStep<AlbumDetail>,
    val chapterTemplate: JmxLiveReadingStep<ChapterTemplate>,
    val imageTransfer: JmxLiveReadingStep<ChapterImageTransferReport>,
    val acceptance: JmxLiveReadingAcceptance,
    val issues: List<JmxDiagnosticIssue>,
    val health: JmxCoreHealth,
    val outputDirectory: String
) {
    val isSuccessful: Boolean = acceptance.meetsMinimum
}

class JmxLiveReadingRunner(
    private val core: JmxCore
) {
    suspend fun run(scenario: JmxLiveReadingScenario): JmxLiveReadingReport {
        val initialization = if (scenario.initialize) {
            runStep("initialize") { core.initializer.initialize() }
        } else {
            JmxLiveReadingStep.success(
                "initialize",
                JmxCoreInitResult(
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

        val albumDetail = runResultStep("album_detail") {
            core.albumApi.detailFull(scenario.albumId)
        }

        val chapterTemplate = runResultStep("chapter_template") {
            core.chapterApi.template(
                chapterId = scenario.chapterId,
                shunt = scenario.shunt
            )
        }

        val imageTransfer: JmxLiveReadingStep<ChapterImageTransferReport> =
            when (val template = chapterTemplate.valueOrNull()) {
                null -> JmxLiveReadingStep.skipped(
                    name = "image_transfer",
                    reason = "chapter_template failed"
                )
                else -> {
                    val limited = template.copy(
                        imageFileNames = template.imageFileNames.take(scenario.maxImages.coerceAtLeast(1))
                    )
                    runStep("image_transfer") {
                        createTransferRunner(scenario).transfer(
                            template = limited,
                            options = ChapterImageTransferOptions(
                                headers = null,
                                acceptedContentTypes = setOf("image/*"),
                                maxBytes = scenario.maxImageBytes
                            )
                        )
                    }
                }
            }

        val transferReport = imageTransfer.valueOrNull()
        val acceptance = JmxLiveReadingAcceptance(
            initOk = initialization.isSuccessful || initialization.isSkipped,
            albumOk = albumDetail.isSuccessful,
            templateOk = chapterTemplate.isSuccessful,
            imageTransferOk = imageTransfer.isSuccessful &&
                (transferReport?.failedCount ?: 1) == 0 &&
                (transferReport?.totalCount ?: 0) > 0,
            restoredOrOriginalOk = transferReport != null &&
                transferReport.restoredOrOriginalCount > 0 &&
                transferReport.storedCount > 0
        )

        val issues = buildList {
            listOf(initialization, albumDetail, chapterTemplate, imageTransfer).forEach { step ->
                step.errorOrNull()?.let {
                    add(
                        JmxDiagnosticIssue(
                            step = step.name,
                            severity = it.diagnosticSeverity(),
                            message = it.message,
                            error = it
                        )
                    )
                }
                step.skippedReason?.let {
                    add(
                        JmxDiagnosticIssue(
                            step = step.name,
                            severity = JmxDiagnosticSeverity.Info,
                            message = it
                        )
                    )
                }
            }
        }

        return JmxLiveReadingReport(
            scenario = scenario,
            initialization = initialization,
            albumDetail = albumDetail,
            chapterTemplate = chapterTemplate,
            imageTransfer = imageTransfer,
            acceptance = acceptance,
            issues = issues,
            health = core.healthSnapshot(),
            outputDirectory = scenario.outputDirectory.toAbsolutePath().toString()
        )
    }

    private fun createTransferRunner(scenario: JmxLiveReadingScenario): ChapterImageTransferRunner {
        val store = FileImageOutputStore(scenario.outputDirectory)
        val restoreRunner = ImageRestoreBatchRunner(
            executor = ImageRestoreExecutor(core.downloader, scenario.imageRowCodec),
            maxConcurrency = core.downloadBatchRunner.maxConcurrency
        )
        return ChapterImageTransferRunner(
            restoreBatchRunner = restoreRunner,
            storeBatchRunner = ImageStoreBatchRunner(store)
        )
    }

    private suspend fun <T> runStep(name: String, block: suspend () -> T): JmxLiveReadingStep<T> {
        return try {
            JmxLiveReadingStep.success(name, block())
        } catch (t: Throwable) {
            JmxLiveReadingStep.failure(name, JmxError.Unknown(t.message ?: "reading step failed", t))
        }
    }

    private suspend fun <T> runResultStep(
        name: String,
        block: suspend () -> JmxResult<T>
    ): JmxLiveReadingStep<T> {
        return try {
            JmxLiveReadingStep(name = name, result = block())
        } catch (t: Throwable) {
            JmxLiveReadingStep.failure(name, JmxError.Unknown(t.message ?: "reading step failed", t))
        }
    }
}

class JmxLiveReadingMarkdownRenderer {
    fun render(report: JmxLiveReadingReport): String {
        return buildString {
            appendLine("# JMComicX Live Reading Report")
            appendLine()
            appendLine("- Meets minimum: `${report.acceptance.meetsMinimum}`")
            appendLine("- Acceptance: `${report.acceptance.passedCount}/${report.acceptance.totalCount}`")
            appendLine("- Output: `${sanitizeDiagnosticText(report.outputDirectory)}`")
            appendLine("- API version: `${sanitizeDiagnosticText(report.health.apiVersion)}`")
            appendLine()
            appendLine("## Acceptance")
            appendLine()
            appendLine("| Gate | Pass |")
            appendLine("| --- | --- |")
            appendLine("| initialize | `${report.acceptance.initOk}` |")
            appendLine("| album | `${report.acceptance.albumOk}` |")
            appendLine("| chapter template | `${report.acceptance.templateOk}` |")
            appendLine("| image transfer | `${report.acceptance.imageTransferOk}` |")
            appendLine("| restore/store | `${report.acceptance.restoredOrOriginalOk}` |")
            appendLine()
            appendLine("## Details")
            appendLine()
            report.albumDetail.valueOrNull()?.let {
                appendLine("- Album: `${it.id}` `${sanitizeDiagnosticText(it.name ?: "-")}`")
            }
            report.chapterTemplate.valueOrNull()?.let {
                appendLine(
                    "- Template: chapter=`${it.chapterId}` images=${it.imageFileNames.size} " +
                        "scramble=${it.scrambleId} host=`${sanitizeDiagnosticText(it.imageHost)}`"
                )
            }
            report.imageTransfer.valueOrNull()?.let {
                appendLine(
                    "- Transfer: total=${it.totalCount} restoredOrOriginal=${it.restoredOrOriginalCount} " +
                        "stored=${it.storedCount} failed=${it.failedCount}"
                )
                it.restoreResults.forEach { item ->
                    when (val r = item.result) {
                        is JmxResult.Success -> appendLine(
                            "  - #${item.item.index} OK restored=${r.value.restored} " +
                                "bytes=${r.value.bytes.size} segments=${r.value.plan.segmentCount}"
                        )
                        is JmxResult.Failure -> appendLine(
                            "  - #${item.item.index} FAIL `${sanitizeDiagnosticText(r.error.message)}`"
                        )
                    }
                }
            }
            if (report.issues.isNotEmpty()) {
                appendLine()
                appendLine("## Issues")
                appendLine()
                report.issues.forEach {
                    appendLine("- `[${it.severity}] ${it.step}`: ${sanitizeDiagnosticText(it.message)}")
                }
            }
            appendLine()
        }
    }
}
