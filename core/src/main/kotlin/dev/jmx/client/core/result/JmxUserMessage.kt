package dev.jmx.client.core.result

data class JmxUserMessage(
    val title: String,
    val userMessage: String,
    val retryable: Boolean,
    val actions: List<JmxRecoveryAction>,
    val kind: JmxErrorKind
)

fun JmxError.toUserMessage(): JmxUserMessage {
    val descriptor = describe()
    return when (this) {
        is JmxError.Network -> JmxUserMessage(
            title = "网络异常",
            userMessage = "网络连接失败，请检查网络后重试，或切换线路。",
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Network
        )
        is JmxError.Http -> JmxUserMessage(
            title = httpTitle(code),
            userMessage = httpUserMessage(code),
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Http
        )
        is JmxError.Api -> JmxUserMessage(
            title = "服务返回错误",
            userMessage = apiUserMessage(code, message),
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Api
        )
        is JmxError.Decode -> JmxUserMessage(
            title = "数据解析失败",
            userMessage = if (message.contains("gzip", ignoreCase = true)) {
                "响应解压或解密失败，请切换线路后重试。"
            } else {
                "服务器返回的数据无法解析，请稍后重试或切换线路。"
            },
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Decode
        )
        is JmxError.Schema -> JmxUserMessage(
            title = if (exchange == null) "参数错误" else "数据格式异常",
            userMessage = if (exchange == null) {
                field?.let { "请求参数无效（$it），请检查后重试。" }
                    ?: "请求参数无效，请检查后重试。"
            } else {
                "服务器返回的数据结构与预期不符，请更新应用或稍后重试。"
            },
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Schema
        )
        is JmxError.Domain -> JmxUserMessage(
            title = "线路不可用",
            userMessage = "当前线路不可用，正在尝试其他线路，或请手动切换/刷新线路。",
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Domain
        )
        is JmxError.Unknown -> JmxUserMessage(
            title = "未知错误",
            userMessage = "发生未知错误，请稍后重试。若持续出现，请导出诊断信息反馈。",
            retryable = retryable,
            actions = descriptor.actions,
            kind = JmxErrorKind.Unknown
        )
    }
}

private fun httpTitle(code: Int): String = when (code) {
    401 -> "需要登录"
    403 -> "访问被拒绝"
    404 -> "内容不存在"
    408, 504, 524 -> "请求超时"
    429 -> "请求过于频繁"
    in 500..599 -> "服务器繁忙"
    else -> "请求失败"
}

private fun httpUserMessage(code: Int): String = when (code) {
    401 -> "登录已失效或未登录，请重新登录。"
    403 -> "访问被拒绝，可能是地区限制、线路异常或账号权限问题，请切换线路或重新登录。"
    404 -> "请求的内容不存在或已被移除。"
    408, 504, 524 -> "服务器响应超时，请稍后重试或切换线路。"
    429 -> "操作过于频繁，请稍后再试。"
    500, 502, 503 -> "服务器暂时繁忙，请稍后重试或切换线路。"
    520 -> "服务器内部异常，请切换线路后重试。"
    else -> "请求失败（HTTP $code），请稍后重试。"
}

private fun apiUserMessage(code: Int, technical: String): String = when (code) {
    401, 403 -> "账号验证失败，请重新登录。"
    404 -> "内容不存在或已被下架。"
    in 500..599 -> "服务暂时不可用，请稍后重试。"
    else -> technical.takeIf { it.isNotBlank() && !it.startsWith("HTTP") }
        ?: "操作失败，请稍后重试。"
}
