package dev.jmx.client.core.runtime

import dev.jmx.client.core.result.JmxErrorDescriptor
import dev.jmx.client.core.result.NetworkExchange
import dev.jmx.client.core.result.describe

data class JmxDiagnosticReport(
    val generatedAtMillis: Long,
    val health: JmxCoreHealth,
    val issues: List<JmxDiagnosticReportIssue>
) {
    val hasErrors: Boolean = issues.any { it.severity == JmxDiagnosticSeverity.Error }
    val hasWarnings: Boolean = issues.any { it.severity == JmxDiagnosticSeverity.Warning }
}

data class JmxDiagnosticReportIssue(
    val step: String,
    val severity: JmxDiagnosticSeverity,
    val message: String,
    val descriptor: JmxErrorDescriptor?,
    val exchange: NetworkExchange?
)

class JmxDiagnosticReportFactory(
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun create(
        health: JmxCoreHealth,
        issues: List<JmxDiagnosticIssue> = emptyList()
    ): JmxDiagnosticReport {
        return JmxDiagnosticReport(
            generatedAtMillis = clockMillis(),
            health = health,
            issues = issues.map { issue ->
                val descriptor = issue.error?.describe()
                JmxDiagnosticReportIssue(
                    step = issue.step,
                    severity = issue.severity,
                    message = sanitizeDiagnosticText(issue.message),
                    descriptor = descriptor,
                    exchange = descriptor?.exchange?.sanitize()
                )
            }
        )
    }
}

class JmxDiagnosticMarkdownRenderer {
    fun render(report: JmxDiagnosticReport): String {
        return buildString {
            appendLine("# JMComicX Core Diagnostic Report")
            appendLine()
            appendLine("- Generated at millis: `${report.generatedAtMillis}`")
            appendLine("- API version: `${sanitizeDiagnosticText(report.health.apiVersion)}`")
            appendLine("- Endpoint mode: `${sanitizeDiagnosticText(report.health.endpointSelection.mode)}`")
            appendLine("- Manual endpoint: `${sanitizeNullable(report.health.endpointSelection.manualUrl)}`")
            appendLine("- Cookie count: `${report.health.cookieCount}`")
            appendLine("- Download concurrency: `${report.health.downloadConcurrency}`")
            appendLine("- Has warnings: `${report.hasWarnings}`")
            appendLine("- Has errors: `${report.hasErrors}`")
            appendLine()
            appendDomainServers(report.health.domainServerUrls)
            appendEndpoints(report.health.endpoints)
            appendIssues(report.issues)
        }.trimEnd() + "\n"
    }

    private fun StringBuilder.appendDomainServers(urls: List<String>) {
        appendLine("## Domain Servers")
        appendLine()
        if (urls.isEmpty()) {
            appendLine("- none")
        } else {
            urls.forEach { appendLine("- `${sanitizeDiagnosticText(it)}`") }
        }
        appendLine()
    }

    private fun StringBuilder.appendEndpoints(endpoints: List<EndpointHealth>) {
        appendLine("## Endpoints")
        appendLine()
        if (endpoints.isEmpty()) {
            appendLine("- none")
            appendLine()
            return
        }
        appendLine("| URL | Available | Score | Success | Failure | Consecutive Failure | Last Latency | Avg Latency | Last Failure |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        endpoints.forEach { endpoint ->
            appendLine(
                "| `${sanitizeDiagnosticText(endpoint.url)}` " +
                    "| `${endpoint.isAvailable}` " +
                    "| ${endpoint.healthScore} " +
                    "| ${endpoint.successCount} " +
                    "| ${endpoint.failureCount} " +
                    "| ${endpoint.consecutiveFailureCount} " +
                    "| ${endpoint.lastLatencyMillis?.toString() ?: "-"} " +
                    "| ${endpoint.averageLatencyMillis?.toString() ?: "-"} " +
                    "| `${sanitizeNullable(endpoint.lastFailureMessage)}` |"
            )
        }
        appendLine()
    }

    private fun StringBuilder.appendIssues(issues: List<JmxDiagnosticReportIssue>) {
        appendLine("## Issues")
        appendLine()
        if (issues.isEmpty()) {
            appendLine("- none")
            appendLine()
            return
        }
        issues.forEachIndexed { index, issue ->
            appendLine("### ${index + 1}. ${sanitizeDiagnosticText(issue.step)}")
            appendLine()
            appendLine("- Severity: `${issue.severity}`")
            appendLine("- Message: `${sanitizeDiagnosticText(issue.message)}`")
            issue.descriptor?.let { descriptor ->
                appendLine("- Kind: `${descriptor.kind}`")
                appendLine("- Impact: `${descriptor.impact}`")
                appendLine("- Retryable: `${descriptor.retryable}`")
                appendLine("- Actions: `${descriptor.actions.joinToString(",")}`")
                appendLine("- Operator hint: `${sanitizeDiagnosticText(descriptor.operatorHint)}`")
                descriptor.httpCode?.let { appendLine("- HTTP code: `$it`") }
                descriptor.apiCode?.let { appendLine("- API code: `$it`") }
                descriptor.field?.let { appendLine("- Field: `${sanitizeDiagnosticText(it)}`") }
                descriptor.endpoint?.let { appendLine("- Endpoint: `${sanitizeDiagnosticText(it)}`") }
            }
            issue.exchange?.let { exchange ->
                appendLine("- Exchange route: `${sanitizeDiagnosticText(exchange.route)}`")
                appendLine("- Exchange URL: `${sanitizeDiagnosticText(exchange.requestUrl)}`")
                appendLine("- Exchange status: `${exchange.statusCode}`")
                appendLine("- Exchange content type: `${sanitizeNullable(exchange.contentType)}`")
                appendLine("- Exchange token timestamp: `${exchange.tokenTimestampSeconds ?: "-"} `")
                appendLine("- Exchange body sample: `${sanitizeDiagnosticText(exchange.bodySample)}`")
            }
            appendLine()
        }
    }
}

fun JmxCore.diagnosticReport(
    issues: List<JmxDiagnosticIssue> = emptyList(),
    generatedAtMillis: Long = System.currentTimeMillis()
): JmxDiagnosticReport {
    return JmxDiagnosticReportFactory { generatedAtMillis }
        .create(health = healthSnapshot(), issues = issues)
}

fun JmxCoreProbeReport.toDiagnosticReport(
    generatedAtMillis: Long = System.currentTimeMillis()
): JmxDiagnosticReport {
    return JmxDiagnosticReportFactory { generatedAtMillis }
        .create(health = afterHealth, issues = issues)
}

fun JmxCoreSmokeReport.toDiagnosticReport(
    generatedAtMillis: Long = System.currentTimeMillis()
): JmxDiagnosticReport {
    return JmxDiagnosticReportFactory { generatedAtMillis }
        .create(health = health, issues = issues)
}

fun sanitizeDiagnosticText(value: String): String {
    return value
        .replace(Regex("""(?i)(token|tokenparam|password|cookie|avs|authorization)["'=:\s]+[^,"'\s}`|]+""")) {
            "${it.groupValues[1]}=<redacted>"
        }
        .replace(Regex("""(?i)([?&](?:token|tokenparam|password|cookie|avs|authorization)=)[^&\s`|]+""")) {
            "${it.groupValues[1]}<redacted>"
        }
        .replace("\r", " ")
        .replace("\n", " ")
        .trim()
}

private fun sanitizeNullable(value: String?): String {
    return value?.let(::sanitizeDiagnosticText) ?: "-"
}

private fun NetworkExchange.sanitize(): NetworkExchange {
    return copy(
        requestUrl = sanitizeDiagnosticText(requestUrl),
        contentType = contentType?.let(::sanitizeDiagnosticText),
        bodySample = sanitizeDiagnosticText(bodySample)
    )
}
