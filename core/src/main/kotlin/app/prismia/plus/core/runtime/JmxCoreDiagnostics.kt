package app.prismia.plus.core.runtime

import app.prismia.plus.core.result.JmxError

enum class JmxDiagnosticSeverity {
    Info,
    Warning,
    Error
}

data class JmxDiagnosticIssue(
    val step: String,
    val severity: JmxDiagnosticSeverity,
    val message: String,
    val error: JmxError? = null
)

fun JmxError.diagnosticSeverity(): JmxDiagnosticSeverity {
    return when (this) {
        is JmxError.Schema,
        is JmxError.Decode,
        is JmxError.Api -> JmxDiagnosticSeverity.Error
        is JmxError.EmptyData -> JmxDiagnosticSeverity.Warning
        is JmxError.Http -> if (retryable) JmxDiagnosticSeverity.Warning else JmxDiagnosticSeverity.Error
        is JmxError.Network,
        is JmxError.Domain -> JmxDiagnosticSeverity.Warning
        is JmxError.Unknown -> JmxDiagnosticSeverity.Error
    }
}
