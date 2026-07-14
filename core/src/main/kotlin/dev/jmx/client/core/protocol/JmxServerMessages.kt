package dev.jmx.client.core.protocol

object JmxServerMessages {
    val responseTextHints: Map<String, String> = mapOf(
        "Could not connect to mysql! Please check your database settings!" to "禁漫服务器内部报错",
        "Restricted Access!" to "禁漫拒绝你所在 IP 地区的访问，可尝试切换线路或代理"
    )

    val httpStatusHints: Map<Int, String> = mapOf(
        403 to "IP 地区禁止访问或爬虫被识别",
        500 to "禁漫服务器内部异常（可能过载，可切换线路或稍后重试）",
        520 to "Web server is returning an unknown error（禁漫服务器内部报错）",
        524 to "源站超时（禁漫服务器处理超时）"
    )

    fun describeHttpStatus(code: Int): String {
        return httpStatusHints[code] ?: "HTTP 状态码 $code"
    }

    fun describeBodyIfKnown(body: String): String? {
        if (body.isBlank()) return null
        for ((needle, hint) in responseTextHints) {
            if (body.contains(needle, ignoreCase = true)) {
                return hint
            }
        }
        return null
    }

    fun composeHttpFailureMessage(code: Int, body: String?): String {
        val statusHint = describeHttpStatus(code)
        val bodyHint = body?.let { describeBodyIfKnown(it) }
        return if (bodyHint != null) {
            "HTTP 请求失败：$code（$statusHint）；$bodyHint"
        } else {
            "HTTP 请求失败：$code（$statusHint）"
        }
    }
}
