package dev.jmx.client.core.result

enum class JmxErrorKind {
    Network,
    Http,
    Api,
    Decode,
    Schema,
    Domain,
    Unknown
}

enum class JmxErrorImpact {
    Warning,
    Error
}

enum class JmxRecoveryAction {
    Retry,
    RetryLater,
    SwitchEndpoint,
    RefreshEndpoints,
    Reauthenticate,
    InspectProtocol,
    FixCallerInput,
    ExportDiagnostics
}

data class JmxErrorDescriptor(
    val kind: JmxErrorKind,
    val impact: JmxErrorImpact,
    val technicalMessage: String,
    val operatorHint: String,
    val retryable: Boolean,
    val actions: List<JmxRecoveryAction>,
    val httpCode: Int? = null,
    val apiCode: Int? = null,
    val field: String? = null,
    val endpoint: String? = null,
    val exchange: NetworkExchange? = null
)

fun JmxError.describe(): JmxErrorDescriptor {
    return when (this) {
        is JmxError.Network -> JmxErrorDescriptor(
            kind = JmxErrorKind.Network,
            impact = JmxErrorImpact.Warning,
            technicalMessage = message,
            operatorHint = "Network transport failed before a usable response was produced.",
            retryable = retryable,
            actions = listOf(JmxRecoveryAction.Retry, JmxRecoveryAction.SwitchEndpoint, JmxRecoveryAction.ExportDiagnostics)
        )
        is JmxError.Http -> JmxErrorDescriptor(
            kind = JmxErrorKind.Http,
            impact = if (retryable) JmxErrorImpact.Warning else JmxErrorImpact.Error,
            technicalMessage = message,
            operatorHint = httpOperatorHint(code),
            retryable = retryable,
            actions = httpRecoveryActions(code, retryable),
            httpCode = code,
            exchange = exchange
        )
        is JmxError.Api -> JmxErrorDescriptor(
            kind = JmxErrorKind.Api,
            impact = JmxErrorImpact.Error,
            technicalMessage = message,
            operatorHint = apiOperatorHint(code),
            retryable = retryable,
            actions = apiRecoveryActions(code),
            apiCode = code,
            exchange = exchange
        )
        is JmxError.Decode -> JmxErrorDescriptor(
            kind = JmxErrorKind.Decode,
            impact = JmxErrorImpact.Error,
            technicalMessage = message,
            operatorHint = "Response decoding failed. Check token timestamp binding, data secret, and envelope format.",
            retryable = retryable,
            actions = listOf(JmxRecoveryAction.InspectProtocol, JmxRecoveryAction.ExportDiagnostics),
            exchange = exchange
        )
        is JmxError.Schema -> JmxErrorDescriptor(
            kind = JmxErrorKind.Schema,
            impact = JmxErrorImpact.Error,
            technicalMessage = message,
            operatorHint = schemaOperatorHint(exchange),
            retryable = retryable,
            actions = schemaRecoveryActions(exchange),
            field = field,
            exchange = exchange
        )
        is JmxError.Domain -> JmxErrorDescriptor(
            kind = JmxErrorKind.Domain,
            impact = JmxErrorImpact.Warning,
            technicalMessage = message,
            operatorHint = "Endpoint selection or domain refresh failed.",
            retryable = retryable,
            actions = listOf(JmxRecoveryAction.RefreshEndpoints, JmxRecoveryAction.SwitchEndpoint, JmxRecoveryAction.ExportDiagnostics),
            endpoint = endpoint
        )
        is JmxError.Unknown -> JmxErrorDescriptor(
            kind = JmxErrorKind.Unknown,
            impact = JmxErrorImpact.Error,
            technicalMessage = message,
            operatorHint = "An unexpected core error escaped the typed failure path.",
            retryable = retryable,
            actions = listOf(JmxRecoveryAction.ExportDiagnostics)
        )
    }
}

fun JmxError.exchangeOrNull(): NetworkExchange? {
    return when (this) {
        is JmxError.Api -> exchange
        is JmxError.Decode -> exchange
        is JmxError.Http -> exchange
        is JmxError.Schema -> exchange
        is JmxError.Domain,
        is JmxError.Network,
        is JmxError.Unknown -> null
    }
}

private fun httpOperatorHint(code: Int): String {
    return when (code) {
        401 -> "认证失败，会话可能缺失或过期。"
        403 -> "访问被拒绝：可能是 IP 地区限制、线路异常、token 或 cookie 问题。"
        404 -> "所选线路上找不到该路由。"
        408 -> "线路在完成请求前超时。"
        429 -> "线路限流，请稍后重试或切换线路。"
        500 -> "禁漫服务器内部异常（可能过载）。"
        520 -> "网关未知错误（禁漫服务器内部报错）。"
        524 -> "源站处理超时。"
        in 500..599 -> "所选线路返回服务端错误。"
        else -> "线路返回了非成功 HTTP 状态。"
    }
}

private fun httpRecoveryActions(code: Int, retryable: Boolean): List<JmxRecoveryAction> {
    return when (code) {
        401, 403 -> listOf(
            JmxRecoveryAction.Reauthenticate,
            JmxRecoveryAction.SwitchEndpoint,
            JmxRecoveryAction.InspectProtocol,
            JmxRecoveryAction.ExportDiagnostics
        )
        408, 429 -> listOf(JmxRecoveryAction.RetryLater, JmxRecoveryAction.SwitchEndpoint, JmxRecoveryAction.ExportDiagnostics)
        in 500..599 -> listOf(JmxRecoveryAction.RetryLater, JmxRecoveryAction.SwitchEndpoint, JmxRecoveryAction.ExportDiagnostics)
        else -> if (retryable) {
            listOf(JmxRecoveryAction.Retry, JmxRecoveryAction.SwitchEndpoint, JmxRecoveryAction.ExportDiagnostics)
        } else {
            listOf(JmxRecoveryAction.InspectProtocol, JmxRecoveryAction.ExportDiagnostics)
        }
    }
}

private fun apiOperatorHint(code: Int): String {
    return when (code) {
        401, 403 -> "业务层拒绝请求，请检查登录态、token 与线路。"
        404 -> "资源不存在或已被下架。"
        in 500..599 -> "业务层报告服务端错误。"
        else -> "业务层返回了应用级错误。"
    }
}

private fun apiRecoveryActions(code: Int): List<JmxRecoveryAction> {
    return when (code) {
        401, 403 -> listOf(
            JmxRecoveryAction.Reauthenticate,
            JmxRecoveryAction.SwitchEndpoint,
            JmxRecoveryAction.InspectProtocol,
            JmxRecoveryAction.ExportDiagnostics
        )
        in 500..599 -> listOf(JmxRecoveryAction.RetryLater, JmxRecoveryAction.SwitchEndpoint, JmxRecoveryAction.ExportDiagnostics)
        else -> listOf(JmxRecoveryAction.InspectProtocol, JmxRecoveryAction.ExportDiagnostics)
    }
}

private fun schemaOperatorHint(exchange: NetworkExchange?): String {
    return if (exchange == null) {
        "Caller input or local data did not match the expected core schema."
    } else {
        "The remote response shape did not match the current parser or mapper."
    }
}

private fun schemaRecoveryActions(exchange: NetworkExchange?): List<JmxRecoveryAction> {
    return if (exchange == null) {
        listOf(JmxRecoveryAction.FixCallerInput, JmxRecoveryAction.ExportDiagnostics)
    } else {
        listOf(JmxRecoveryAction.InspectProtocol, JmxRecoveryAction.ExportDiagnostics)
    }
}
