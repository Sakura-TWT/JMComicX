package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.protocol.JmxServerMessages
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

object ResponseBodyInspector {
    fun inspect(
        route: ApiRoute,
        statusCode: Int,
        body: String
    ): JmxResult<Unit> {
        if (body.isBlank()) {
            return JmxResult.Failure(
                JmxError.Network(
                    message = "响应无数据：${route.path}",
                    retryable = true
                )
            )
        }
        JmxServerMessages.describeBodyIfKnown(body)?.let { hint ->
            return JmxResult.Failure(
                JmxError.Http(
                    code = statusCode,
                    message = "禁漫异常响应：$hint",
                    retryable = true
                )
            )
        }
        if (!route.encryptedJson) {
            return JmxResult.Success(Unit)
        }
        val first = body.firstNonWhitespaceOrNull()
            ?: return JmxResult.Failure(
                JmxError.Network(
                    message = "响应无有效数据：${route.path}",
                    retryable = true
                )
            )
        if (first != '{') {
            val sample = body.trim().take(200)
            val gzipHint = if (looksLikeGzip(body)) {
                "；疑似未解压的 gzip 响应（请勿手动设置 Accept-Encoding，应让 OkHttp 透明解压）"
            } else {
                ""
            }

            return JmxResult.Failure(
                JmxError.Decode(
                    message = "请求不是 JSON 格式，强制切换线路重试$gzipHint；响应文本：[$sample]",
                    retryable = true
                )
            )
        }
        return JmxResult.Success(Unit)
    }

    private fun String.firstNonWhitespaceOrNull(): Char? {
        for (char in this) {
            if (!char.isWhitespace()) return char
        }
        return null
    }

    private fun looksLikeGzip(body: String): Boolean {
        if (body.length < 2) return false
        val b0 = body[0].code and 0xff
        val b1 = body[1].code and 0xff

        return b0 == 0x1f && b1 == 0x8b
    }
}
