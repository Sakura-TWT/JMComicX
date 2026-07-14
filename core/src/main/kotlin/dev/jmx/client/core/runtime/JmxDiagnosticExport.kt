package dev.jmx.client.core.runtime

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.result.describe
import dev.jmx.client.core.session.SessionManager
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class JmxDiagnosticExportRequest(
    val issues: List<JmxDiagnosticIssue> = emptyList(),
    val includeMarkdown: Boolean = true,
    val includeHealthText: Boolean = true,
    val includeCookieMeta: Boolean = true,
    val includeTaskSummary: Boolean = true
)

data class JmxDiagnosticExportResult(
    val archivePath: Path,
    val entryNames: List<String>,
    val byteSize: Long
)

class JmxDiagnosticExporter(
    private val core: JmxCore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun exportToDirectory(
        directory: Path,
        request: JmxDiagnosticExportRequest = JmxDiagnosticExportRequest()
    ): JmxResult<JmxDiagnosticExportResult> {
        return runCatching {
            Files.createDirectories(directory)
            val stamp = clockMillis()
            val archive = directory.resolve("jmx-diagnostics-$stamp.zip")
            val entries = linkedMapOf<String, ByteArray>()

            val report = core.diagnosticReport(issues = request.issues, generatedAtMillis = stamp)
            if (request.includeMarkdown) {
                entries["summary.md"] = JmxDiagnosticMarkdownRenderer()
                    .render(report)
                    .toByteArray(StandardCharsets.UTF_8)
            }
            if (request.includeHealthText) {
                entries["health.txt"] = renderHealthText(report).toByteArray(StandardCharsets.UTF_8)
            }
            if (request.includeCookieMeta) {
                entries["cookies-meta.txt"] = renderCookieMeta(core.sessionManager)
                    .toByteArray(StandardCharsets.UTF_8)
            }
            if (request.includeTaskSummary) {
                entries["download-tasks.txt"] = renderTaskSummary(core)
                    .toByteArray(StandardCharsets.UTF_8)
            }
            entries["redaction-note.txt"] = (
                "This package is redacted.\n" +
                    "No passwords, AVS values, or raw cookie values are included.\n" +
                    "Cookie section lists names/domains/paths only.\n"
                ).toByteArray(StandardCharsets.UTF_8)

            val bytes = zipEntries(entries)
            Files.write(archive, bytes)
            JmxDiagnosticExportResult(
                archivePath = archive,
                entryNames = entries.keys.toList(),
                byteSize = bytes.size.toLong()
            )
        }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = {
                JmxResult.Failure(
                    JmxError.Unknown(it.message ?: "诊断导出失败", it)
                )
            }
        )
    }

    fun exportBytes(
        request: JmxDiagnosticExportRequest = JmxDiagnosticExportRequest()
    ): JmxResult<Pair<ByteArray, List<String>>> {
        return runCatching {
            val stamp = clockMillis()
            val report = core.diagnosticReport(issues = request.issues, generatedAtMillis = stamp)
            val entries = linkedMapOf<String, ByteArray>()
            if (request.includeMarkdown) {
                entries["summary.md"] = JmxDiagnosticMarkdownRenderer()
                    .render(report)
                    .toByteArray(StandardCharsets.UTF_8)
            }
            if (request.includeHealthText) {
                entries["health.txt"] = renderHealthText(report).toByteArray(StandardCharsets.UTF_8)
            }
            if (request.includeCookieMeta) {
                entries["cookies-meta.txt"] = renderCookieMeta(core.sessionManager)
                    .toByteArray(StandardCharsets.UTF_8)
            }
            if (request.includeTaskSummary) {
                entries["download-tasks.txt"] = renderTaskSummary(core)
                    .toByteArray(StandardCharsets.UTF_8)
            }
            entries["redaction-note.txt"] = "redacted package\n".toByteArray(StandardCharsets.UTF_8)
            zipEntries(entries) to entries.keys.toList()
        }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Unknown(it.message ?: "诊断导出失败", it)) }
        )
    }

    private fun renderHealthText(report: JmxDiagnosticReport): String {
        return buildString {
            appendLine("apiVersion=${sanitizeDiagnosticText(report.health.apiVersion)}")
            appendLine("endpointMode=${report.health.endpointSelection.mode}")
            appendLine("manualEndpoint=${sanitizeNullable(report.health.endpointSelection.manualUrl)}")
            appendLine("cookieCount=${report.health.cookieCount}")
            appendLine("downloadConcurrency=${report.health.downloadConcurrency}")
            appendLine("endpoints=${report.health.endpoints.size}")
            report.health.endpoints.forEach { ep ->
                appendLine(
                    "endpoint url=${sanitizeDiagnosticText(ep.url)} " +
                        "available=${ep.isAvailable} score=${ep.healthScore} " +
                        "success=${ep.successCount} failure=${ep.failureCount} " +
                        "lastFailure=${sanitizeNullable(ep.lastFailureMessage)}"
                )
            }
            appendLine("issues=${report.issues.size}")
            report.issues.forEach { issue ->
                appendLine(
                    "issue step=${sanitizeDiagnosticText(issue.step)} " +
                        "severity=${issue.severity} msg=${sanitizeDiagnosticText(issue.message)}"
                )
                issue.descriptor?.let { d ->
                    appendLine(
                        "  kind=${d.kind} retryable=${d.retryable} actions=${d.actions.joinToString(",")}"
                    )
                }
            }
        }
    }

    private fun renderCookieMeta(sessionManager: SessionManager): String {
        val cookies = sessionManager.cookies()
        return buildString {
            appendLine("count=${cookies.size}")
            cookies.forEach { cookie ->

                appendLine(
                    "cookie name=${cookie.name} domain=${cookie.domain} path=${cookie.path} " +
                        "hostOnly=${cookie.hostOnly} httpOnly=${cookie.httpOnly} " +
                        "valueLength=${cookie.value.length}"
                )
            }
        }
    }

    private fun renderTaskSummary(core: JmxCore): String {
        val tasks = core.chapterDownloadTasks.list()
        return buildString {
            appendLine("taskCount=${tasks.size}")
            tasks.forEach { task ->
                appendLine(
                    "task id=${task.id} state=${task.state} " +
                        "album=${task.spec.albumId} chapter=${task.spec.chapterId} " +
                        "progress=${task.progress?.completed ?: 0}/${task.progress?.total ?: 0} " +
                        "completedFiles=${task.completedImageFileNames.size} " +
                        "error=${sanitizeNullable(task.error?.message)}"
                )
            }
        }
    }

    private fun zipEntries(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun sanitizeNullable(value: String?): String {
        return value?.let(::sanitizeDiagnosticText) ?: "-"
    }
}

fun JmxCore.exportDiagnostics(
    directory: Path,
    issues: List<JmxDiagnosticIssue> = emptyList()
): JmxResult<JmxDiagnosticExportResult> {
    return JmxDiagnosticExporter(this).exportToDirectory(
        directory = directory,
        request = JmxDiagnosticExportRequest(issues = issues)
    )
}
