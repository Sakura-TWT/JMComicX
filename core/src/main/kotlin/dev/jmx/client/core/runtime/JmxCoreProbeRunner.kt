package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.RemoteSetting
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.network.ApiEndpoint
import dev.jmx.client.core.network.ApiEndpointProbeResult
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

data class JmxCoreProbeScenario(
    val refreshDomains: Boolean = true,
    val probeEndpoints: Boolean = false,
    val fetchSetting: Boolean = true,
    val chapterTemplate: ChapterTemplateProbe? = null,
    val requireSession: Boolean = false
)

data class ChapterTemplateProbe(
    val chapterId: String,
    val shunt: String,
    val page: Int = 0,
    val mode: String = "vertical",
    val express: String = "off",
    val timestampSeconds: Long = System.currentTimeMillis() / 1000L
)

data class JmxCoreProbeStep<T>(
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
        fun <T> success(name: String, value: T): JmxCoreProbeStep<T> {
            return JmxCoreProbeStep(name = name, result = JmxResult.Success(value))
        }

        fun <T> failure(name: String, error: JmxError): JmxCoreProbeStep<T> {
            return JmxCoreProbeStep(name = name, result = JmxResult.Failure(error))
        }

        fun <T> skipped(name: String, reason: String): JmxCoreProbeStep<T> {
            return JmxCoreProbeStep(name = name, skippedReason = reason)
        }
    }
}

data class SessionProbeResult(
    val cookieCount: Int,
    val hasAvs: Boolean
)

data class JmxCoreProbeReport(
    val beforeHealth: JmxCoreHealth,
    val domainRefresh: JmxCoreProbeStep<List<ApiEndpoint>>,
    val endpointProbe: JmxCoreProbeStep<List<ApiEndpointProbeResult>>,
    val setting: JmxCoreProbeStep<RemoteSetting>,
    val session: JmxCoreProbeStep<SessionProbeResult>,
    val chapterTemplate: JmxCoreProbeStep<ChapterTemplate>,
    val afterHealth: JmxCoreHealth
) {
    val isSuccessful: Boolean =
        listOf(domainRefresh, endpointProbe, setting, session, chapterTemplate)
            .filterNot { it.isSkipped }
            .all { it.isSuccessful }

    val issues: List<JmxDiagnosticIssue> = buildList {
        listOf(domainRefresh, endpointProbe, setting, session, chapterTemplate).forEach { step ->
            step.toIssueOrNull()?.let(::add)
        }
    }

    val failedSteps: List<String> = issues
        .filter { it.severity != JmxDiagnosticSeverity.Info }
        .map { it.step }

    val skippedSteps: List<String> = issues
        .filter { it.severity == JmxDiagnosticSeverity.Info }
        .map { it.step }
}

class JmxCoreProbeRunner(
    private val core: JmxCore
) {
    suspend fun run(scenario: JmxCoreProbeScenario = JmxCoreProbeScenario()): JmxCoreProbeReport {
        val beforeHealth = core.healthSnapshot()
        val domainRefresh = if (scenario.refreshDomains) {
            runResultStep("domain_refresh") { core.domainRefresher.refresh() }
        } else {
            JmxCoreProbeStep.skipped("domain_refresh", "refreshDomains=false")
        }
        val endpointProbe = if (scenario.probeEndpoints) {
            runPlainStep("endpoint_probe") { core.endpointProber.probeAll() }
        } else {
            JmxCoreProbeStep.skipped("endpoint_probe", "probeEndpoints=false")
        }
        val setting = if (scenario.fetchSetting) {
            runResultStep("setting") { core.settingApi.fetchSetting() }
        } else {
            JmxCoreProbeStep.skipped("setting", "fetchSetting=false")
        }
        val session = probeSession(scenario.requireSession)
        val chapterTemplate = scenario.chapterTemplate?.let { probe ->
            runResultStep("chapter_template") {
                core.chapterApi.template(
                    chapterId = probe.chapterId,
                    shunt = probe.shunt,
                    page = probe.page,
                    mode = probe.mode,
                    express = probe.express,
                    timestampSeconds = probe.timestampSeconds
                )
            }
        } ?: JmxCoreProbeStep.skipped("chapter_template", "chapterTemplate=null")
        return JmxCoreProbeReport(
            beforeHealth = beforeHealth,
            domainRefresh = domainRefresh,
            endpointProbe = endpointProbe,
            setting = setting,
            session = session,
            chapterTemplate = chapterTemplate,
            afterHealth = core.healthSnapshot()
        )
    }

    private fun probeSession(requireSession: Boolean): JmxCoreProbeStep<SessionProbeResult> {
        val cookies = core.sessionManager.cookies()
        val result = SessionProbeResult(
            cookieCount = cookies.size,
            hasAvs = cookies.any { it.name.equals("AVS", ignoreCase = true) }
        )
        return if (requireSession && !result.hasAvs) {
            JmxCoreProbeStep.failure("session", JmxError.Schema("登录态 AVS 不存在", field = "AVS"))
        } else {
            JmxCoreProbeStep.success("session", result)
        }
    }

    private suspend fun <T> runResultStep(
        name: String,
        block: suspend () -> JmxResult<T>
    ): JmxCoreProbeStep<T> {
        return runCatching { block() }.fold(
            onSuccess = { JmxCoreProbeStep(name = name, result = it) },
            onFailure = { JmxCoreProbeStep.failure(name, JmxError.Unknown(it.message ?: "探测步骤异常", it)) }
        )
    }

    private suspend fun <T> runPlainStep(
        name: String,
        block: suspend () -> T
    ): JmxCoreProbeStep<T> {
        return runCatching { block() }.fold(
            onSuccess = { JmxCoreProbeStep.success(name, it) },
            onFailure = { JmxCoreProbeStep.failure(name, JmxError.Unknown(it.message ?: "探测步骤异常", it)) }
        )
    }
}

private fun JmxCoreProbeStep<*>.toIssueOrNull(): JmxDiagnosticIssue? {
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
