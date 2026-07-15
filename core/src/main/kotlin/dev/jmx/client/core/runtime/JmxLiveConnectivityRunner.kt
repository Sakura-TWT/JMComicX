package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.api.RemoteSetting
import dev.jmx.client.core.api.SearchPage
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.network.ApiEndpoint
import dev.jmx.client.core.network.ApiEndpointProbeResult
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.result.describe
import dev.jmx.client.core.result.exchangeOrNull

object JmxLiveProbeDefaults {
    const val SAMPLE_ALBUM_ID = "438516"
    const val SAMPLE_CHAPTER_ID = SAMPLE_ALBUM_ID
    const val SAMPLE_SEARCH_QUERY = "无修正"
}

data class JmxLiveConnectivityScenario(
    val refreshDomains: Boolean = true,
    val probeEndpoints: Boolean = true,
    val fetchSetting: Boolean = true,
    val albumId: String? = DEFAULT_ALBUM_ID,
    val searchQuery: String? = DEFAULT_SEARCH_QUERY,
    val searchPage: Int = 1,
    val chapterId: String? = DEFAULT_CHAPTER_ID,
    val shunt: String = "1"
) {
    companion object {

        const val DEFAULT_ALBUM_ID = JmxLiveProbeDefaults.SAMPLE_ALBUM_ID
        const val DEFAULT_CHAPTER_ID = JmxLiveProbeDefaults.SAMPLE_CHAPTER_ID
        const val DEFAULT_SEARCH_QUERY = JmxLiveProbeDefaults.SAMPLE_SEARCH_QUERY
    }
}

data class JmxLiveConnectivityStep<T>(
    val name: String,
    val result: JmxResult<T>? = null,
    val skippedReason: String? = null
) {
    val isSuccessful: Boolean = result is JmxResult.Success
    val isSkipped: Boolean = skippedReason != null

    fun valueOrNull(): T? = (result as? JmxResult.Success)?.value
    fun errorOrNull(): JmxError? = (result as? JmxResult.Failure)?.error

    companion object {
        fun <T> success(name: String, value: T): JmxLiveConnectivityStep<T> =
            JmxLiveConnectivityStep(name = name, result = JmxResult.Success(value))

        fun <T> failure(name: String, error: JmxError): JmxLiveConnectivityStep<T> =
            JmxLiveConnectivityStep(name = name, result = JmxResult.Failure(error))

        fun <T> skipped(name: String, reason: String): JmxLiveConnectivityStep<T> =
            JmxLiveConnectivityStep(name = name, skippedReason = reason)
    }
}

data class JmxLiveConnectivityAcceptance(
    val hasUsableEndpoint: Boolean,
    val settingOk: Boolean,
    val albumOk: Boolean,
    val searchOk: Boolean,
    val chapterTemplateOk: Boolean
) {

    val meetsMinimum: Boolean =
        hasUsableEndpoint && albumOk && searchOk && chapterTemplateOk

    val passedCount: Int = listOf(
        hasUsableEndpoint,
        settingOk,
        albumOk,
        searchOk,
        chapterTemplateOk
    ).count { it }

    val totalCount: Int = 5
}

data class EndpointProbeSummary(
    val total: Int,
    val successCount: Int,
    val results: List<ApiEndpointProbeResult>
) {
    val hasAnySuccess: Boolean = successCount > 0
}

data class JmxLiveConnectivityReport(
    val scenario: JmxLiveConnectivityScenario,
    val beforeHealth: JmxCoreHealth,
    val domainRefresh: JmxLiveConnectivityStep<List<ApiEndpoint>>,
    val endpointProbe: JmxLiveConnectivityStep<EndpointProbeSummary>,
    val setting: JmxLiveConnectivityStep<RemoteSetting>,
    val albumDetail: JmxLiveConnectivityStep<AlbumDetail>,
    val search: JmxLiveConnectivityStep<SearchPage>,
    val chapterTemplate: JmxLiveConnectivityStep<ChapterTemplate>,
    val afterHealth: JmxCoreHealth,
    val acceptance: JmxLiveConnectivityAcceptance,
    val issues: List<JmxDiagnosticIssue>
) {
    val isSuccessful: Boolean = acceptance.meetsMinimum

    val failedSteps: List<String> = issues
        .filter { it.severity != JmxDiagnosticSeverity.Info }
        .map { it.step }

    fun toDiagnosticReport(generatedAtMillis: Long = System.currentTimeMillis()): JmxDiagnosticReport {
        return JmxDiagnosticReportFactory { generatedAtMillis }
            .create(health = afterHealth, issues = issues)
    }
}

class JmxLiveConnectivityRunner(
    private val core: JmxCore
) {
    suspend fun run(
        scenario: JmxLiveConnectivityScenario = JmxLiveConnectivityScenario()
    ): JmxLiveConnectivityReport {
        val beforeHealth = core.healthSnapshot()

        val domainRefresh = if (scenario.refreshDomains) {
            runResultStep("domain_refresh") { core.domainRefresher.refresh() }
        } else {
            JmxLiveConnectivityStep.skipped("domain_refresh", "refreshDomains=false")
        }

        val endpointProbe = if (scenario.probeEndpoints) {
            runPlainStep("endpoint_probe") {
                val results = core.endpointProber.probeAll()
                EndpointProbeSummary(
                    total = results.size,
                    successCount = results.count { it.success },
                    results = results
                )
            }
        } else {
            JmxLiveConnectivityStep.skipped("endpoint_probe", "probeEndpoints=false")
        }

        val setting = if (scenario.fetchSetting) {
            runResultStep("setting") { core.settingApi.fetchSetting() }
        } else {
            JmxLiveConnectivityStep.skipped("setting", "fetchSetting=false")
        }

        val albumDetail = scenario.albumId?.takeIf { it.isNotBlank() }?.let { albumId ->
            runResultStep("album_detail") { core.albumApi.detailFull(albumId) }
        } ?: JmxLiveConnectivityStep.skipped("album_detail", "albumId=null")

        val search = scenario.searchQuery?.takeIf { it.isNotBlank() }?.let { query ->
            runResultStep("search") {
                core.albumApi.search(
                    query = query,
                    page = scenario.searchPage.coerceAtLeast(1),
                    order = JmxMagicConstants.ORDER_BY_LATEST,
                    time = JmxMagicConstants.TIME_ALL
                )
            }
        } ?: JmxLiveConnectivityStep.skipped("search", "searchQuery=null")

        val chapterTemplate = scenario.chapterId?.takeIf { it.isNotBlank() }?.let { chapterId ->
            val shunt = resolveShunt(scenario.shunt, setting.valueOrNull())
            runResultStep("chapter_template") {
                core.chapterApi.template(chapterId = chapterId, shunt = shunt)
            }
        } ?: JmxLiveConnectivityStep.skipped("chapter_template", "chapterId=null")

        val afterHealth = core.healthSnapshot()
        val acceptance = buildAcceptance(
            beforeHealth = beforeHealth,
            afterHealth = afterHealth,
            domainRefresh = domainRefresh,
            endpointProbe = endpointProbe,
            setting = setting,
            albumDetail = albumDetail,
            search = search,
            chapterTemplate = chapterTemplate
        )
        val issues = collectIssues(
            domainRefresh,
            endpointProbe,
            setting,
            albumDetail,
            search,
            chapterTemplate,
            endpointProbe.valueOrNull()
        )

        return JmxLiveConnectivityReport(
            scenario = scenario,
            beforeHealth = beforeHealth,
            domainRefresh = domainRefresh,
            endpointProbe = endpointProbe,
            setting = setting,
            albumDetail = albumDetail,
            search = search,
            chapterTemplate = chapterTemplate,
            afterHealth = afterHealth,
            acceptance = acceptance,
            issues = issues
        )
    }

    private fun resolveShunt(configured: String, setting: RemoteSetting?): String {
        if (configured.isNotBlank()) return configured
        return setting?.shunts?.firstOrNull()?.id ?: "1"
    }

    private fun buildAcceptance(
        beforeHealth: JmxCoreHealth,
        afterHealth: JmxCoreHealth,
        domainRefresh: JmxLiveConnectivityStep<List<ApiEndpoint>>,
        endpointProbe: JmxLiveConnectivityStep<EndpointProbeSummary>,
        setting: JmxLiveConnectivityStep<RemoteSetting>,
        albumDetail: JmxLiveConnectivityStep<AlbumDetail>,
        search: JmxLiveConnectivityStep<SearchPage>,
        chapterTemplate: JmxLiveConnectivityStep<ChapterTemplate>
    ): JmxLiveConnectivityAcceptance {
        val domainOk = domainRefresh.isSuccessful && (domainRefresh.valueOrNull()?.isNotEmpty() == true)
        val probeOk = endpointProbe.valueOrNull()?.hasAnySuccess == true
        val healthOk = afterHealth.endpoints.any { it.isAvailable } ||
            beforeHealth.endpoints.any { it.isAvailable } ||
            afterHealth.endpoints.isNotEmpty()

        val businessOk = albumDetail.isSuccessful || search.isSuccessful || setting.isSuccessful
        val hasUsableEndpoint = domainOk || probeOk || healthOk || businessOk

        return JmxLiveConnectivityAcceptance(
            hasUsableEndpoint = hasUsableEndpoint,
            settingOk = setting.isSuccessful || setting.isSkipped,
            albumOk = albumDetail.isSuccessful,
            searchOk = search.isSuccessful,
            chapterTemplateOk = chapterTemplate.isSuccessful
        )
    }

    private fun collectIssues(
        domainRefresh: JmxLiveConnectivityStep<*>,
        endpointProbe: JmxLiveConnectivityStep<*>,
        setting: JmxLiveConnectivityStep<*>,
        albumDetail: JmxLiveConnectivityStep<*>,
        search: JmxLiveConnectivityStep<*>,
        chapterTemplate: JmxLiveConnectivityStep<*>,
        probeSummary: EndpointProbeSummary?
    ): List<JmxDiagnosticIssue> {
        return buildList {
            listOf(domainRefresh, endpointProbe, setting, albumDetail, search, chapterTemplate)
                .forEach { step -> step.toIssueOrNull()?.let(::add) }
            probeSummary?.results
                ?.filterNot { it.success }
                ?.forEach { failed ->
                    add(
                        JmxDiagnosticIssue(
                            step = "endpoint_probe.host",
                            severity = (failed.error ?: JmxError.Unknown("probe failed")).diagnosticSeverity(),
                            message = buildString {
                                append(failed.url)
                                append(" → ")
                                append(failed.error?.message ?: "failed")
                                failed.statusCode?.let { append(" (http $it)") }
                                append(" ${failed.latencyMillis}ms")
                            },
                            error = failed.error
                        )
                    )
                }
        }
    }

    private suspend fun <T> runResultStep(
        name: String,
        block: suspend () -> JmxResult<T>
    ): JmxLiveConnectivityStep<T> {
        return runCatching { block() }.fold(
            onSuccess = { JmxLiveConnectivityStep(name = name, result = it) },
            onFailure = {
                JmxLiveConnectivityStep.failure(
                    name,
                    JmxError.Unknown(it.message ?: "连通性步骤异常", it)
                )
            }
        )
    }

    private suspend fun <T> runPlainStep(
        name: String,
        block: suspend () -> T
    ): JmxLiveConnectivityStep<T> {
        return runCatching { block() }.fold(
            onSuccess = { JmxLiveConnectivityStep.success(name, it) },
            onFailure = {
                JmxLiveConnectivityStep.failure(
                    name,
                    JmxError.Unknown(it.message ?: "连通性步骤异常", it)
                )
            }
        )
    }
}

class JmxLiveConnectivityMarkdownRenderer {
    fun render(report: JmxLiveConnectivityReport): String {
        return buildString {
            appendLine("# JMComicX Live Connectivity Report")
            appendLine()
            appendLine("- Generated at millis: `${System.currentTimeMillis()}`")
            appendLine("- Meets minimum: `${report.acceptance.meetsMinimum}`")
            appendLine("- Acceptance: `${report.acceptance.passedCount}/${report.acceptance.totalCount}`")
            appendLine("- API version: `${sanitizeDiagnosticText(report.afterHealth.apiVersion)}`")
            appendLine("- Endpoints: `${report.afterHealth.endpoints.size}`")
            appendLine("- Cookies: `${report.afterHealth.cookieCount}`")
            appendLine()
            appendLine("## Acceptance")
            appendLine()
            appendLine("| Gate | Pass |")
            appendLine("| --- | --- |")
            appendLine("| usable endpoint | `${report.acceptance.hasUsableEndpoint}` |")
            appendLine("| setting | `${report.acceptance.settingOk}` |")
            appendLine("| album detail | `${report.acceptance.albumOk}` |")
            appendLine("| search | `${report.acceptance.searchOk}` |")
            appendLine("| chapter template | `${report.acceptance.chapterTemplateOk}` |")
            appendLine()
            appendLine("## Steps")
            appendLine()
            appendStep(report.domainRefresh) { endpoints ->
                "${endpoints.size} hosts: ${endpoints.take(5).joinToString { it.url.toString() }}"
            }
            appendStep(report.endpointProbe) { summary ->
                "${summary.successCount}/${summary.total} hosts ok"
            }
            appendStep(report.setting) { setting ->
                "version=${setting.apiVersion ?: "-"}, imageHost=${setting.imageHost ?: "-"}, shunts=${setting.shunts.size}"
            }
            appendStep(report.albumDetail) { album ->
                "id=${album.id}, name=${album.name ?: "-"}, series=${album.series.size}, tags=${album.tags.size}"
            }
            appendStep(report.search) { page ->
                "total=${page.total ?: "-"}, content=${page.content.size}, redirect=${page.redirectAlbumId ?: "-"}"
            }
            appendStep(report.chapterTemplate) { template ->
                "chapter=${template.chapterId}, images=${template.imageFileNames.size}, scramble=${template.scrambleId}, host=${template.imageHost}"
            }
            appendLine("## Endpoint Probe Detail")
            appendLine()
            val probeResults = report.endpointProbe.valueOrNull()?.results.orEmpty()
            if (probeResults.isEmpty()) {
                appendLine("- none")
            } else {
                appendLine("| Host | OK | HTTP | Latency | Error |")
                appendLine("| --- | --- | ---: | ---: | --- |")
                probeResults.forEach { item ->
                    appendLine(
                        "| `${sanitizeDiagnosticText(item.url)}` " +
                            "| `${item.success}` " +
                            "| ${item.statusCode ?: "-"} " +
                            "| ${item.latencyMillis} " +
                            "| `${sanitizeDiagnosticText(item.error?.message ?: "-")}` |"
                    )
                }
            }
            appendLine()
            appendLine("## Issues")
            appendLine()
            if (report.issues.isEmpty()) {
                appendLine("- none")
            } else {
                report.issues.forEachIndexed { index, issue ->
                    appendLine("### ${index + 1}. ${sanitizeDiagnosticText(issue.step)}")
                    appendLine()
                    appendLine("- Severity: `${issue.severity}`")
                    appendLine("- Message: `${sanitizeDiagnosticText(issue.message)}`")
                    issue.error?.describe()?.let { descriptor ->
                        appendLine("- Kind: `${descriptor.kind}`")
                        appendLine("- Retryable: `${descriptor.retryable}`")
                        appendLine("- Actions: `${descriptor.actions.joinToString(",")}`")
                        appendLine("- Hint: `${sanitizeDiagnosticText(descriptor.operatorHint)}`")
                    }
                    issue.error?.exchangeOrNull()?.let { exchange ->
                        appendLine("- Route: `${sanitizeDiagnosticText(exchange.route)}`")
                        appendLine("- URL: `${sanitizeDiagnosticText(exchange.requestUrl)}`")
                        appendLine("- Status: `${exchange.statusCode}`")
                        appendLine("- Body sample: `${sanitizeDiagnosticText(exchange.bodySample)}`")
                    }
                    appendLine()
                }
            }
        }.trimEnd() + "\n"
    }

    private fun <T> StringBuilder.appendStep(
        step: JmxLiveConnectivityStep<T>,
        successDetail: (T) -> String
    ) {
        val status = when {
            step.isSkipped -> "SKIP"
            step.isSuccessful -> "OK"
            else -> "FAIL"
        }
        appendLine("### ${step.name} — $status")
        appendLine()
        when {
            step.isSkipped -> appendLine("- Reason: `${sanitizeDiagnosticText(step.skippedReason ?: "")}`")
            step.isSuccessful -> appendLine("- Detail: `${sanitizeDiagnosticText(successDetail(step.valueOrNull()!!))}`")
            else -> {
                val error = step.errorOrNull()
                appendLine("- Error: `${sanitizeDiagnosticText(error?.message ?: "unknown")}`")
                error?.describe()?.let {
                    appendLine("- Kind: `${it.kind}`")
                    appendLine("- Hint: `${sanitizeDiagnosticText(it.operatorHint)}`")
                }
                error?.exchangeOrNull()?.let {
                    appendLine("- Exchange: `${sanitizeDiagnosticText(it.requestUrl)}` status=${it.statusCode}")
                    appendLine("- Body: `${sanitizeDiagnosticText(it.bodySample)}`")
                }
            }
        }
        appendLine()
    }
}

private fun JmxLiveConnectivityStep<*>.toIssueOrNull(): JmxDiagnosticIssue? {
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
