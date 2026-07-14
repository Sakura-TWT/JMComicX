package dev.jmx.client.core.protocol

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

object JmId {
    private val albumOrPhotoPath = Regex(
        """(?i)/(?:album|photo)/(\d+)"""
    )
    private val queryId = Regex(
        """(?i)(?:\?|&)id=(\d+)"""
    )
    private val trailingDigits = Regex(
        """(\d{4,})(?:[/?#].*)?$"""
    )

    fun parse(raw: String): JmxResult<String> {
        val text = raw.trim()
        if (text.isEmpty()) {
            return JmxResult.Failure(JmxError.Schema("无法解析 jm 车号：输入为空", field = "jmId"))
        }
        if (text.all { it.isDigit() }) {
            return JmxResult.Success(text)
        }
        if (text.length >= 2 &&
            text[0].equals('J', ignoreCase = true) &&
            text[1].equals('M', ignoreCase = true)
        ) {
            val digits = text.substring(2).trim()
            if (digits.isNotEmpty() && digits.all { it.isDigit() }) {
                return JmxResult.Success(digits)
            }
        }
        albumOrPhotoPath.find(text)?.groupValues?.getOrNull(1)?.let {
            return JmxResult.Success(it)
        }
        queryId.find(text)?.groupValues?.getOrNull(1)?.let {
            return JmxResult.Success(it)
        }
        trailingDigits.find(text)?.groupValues?.getOrNull(1)?.let {
            return JmxResult.Success(it)
        }
        return JmxResult.Failure(
            JmxError.Schema("无法解析 jm 车号：$text", field = "jmId")
        )
    }

    fun parseOrNull(raw: String): String? {
        return when (val result = parse(raw)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> null
        }
    }

    fun require(raw: String): String {
        return when (val result = parse(raw)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> error(result.error.message)
        }
    }
}
