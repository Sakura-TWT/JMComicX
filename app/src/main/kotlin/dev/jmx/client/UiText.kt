package dev.jmx.client

import dev.jmx.client.core.result.JmxError

internal fun JmxError.toUiMessage(): String {
    return when (this) {
        is JmxError.Network -> "网络请求失败：$message"
        is JmxError.Http -> "线路返回 HTTP $code：$message"
        is JmxError.Api -> "接口返回错误 $code：$message"
        is JmxError.Decode -> "响应解密或解析失败：$message"
        is JmxError.Schema -> "响应结构暂不匹配：$message"
        is JmxError.Domain -> "线路或域名不可用：$message"
        is JmxError.Unknown -> "未知错误：$message"
    }
}

internal fun JmxError.requiresLogin(): Boolean = when (this) {
    is JmxError.Http -> code == 401 || code == 403
    is JmxError.Api -> code == 401 || code == 403
    else -> false
}
