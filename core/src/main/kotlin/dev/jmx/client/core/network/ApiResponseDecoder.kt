package dev.jmx.client.core.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class ApiResponseDecoder(
    private val gson: Gson = Gson(),
    private val bodySampler: BodySampler = BodySampler()
) {
    fun decodeEncryptedEnvelope(body: String, tokenTimestampSeconds: Long): JmxResult<ApiEnvelope> {
        val root = parseJsonObject(body).unwrapOrReturn { return it }
        val code = root.apiCodeOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("API 响应缺少 code 字段：${bodySampler.sample(body)}", field = "code"))
        if (code != 200) {
            return JmxResult.Success(
                ApiEnvelope(
                    code = code,
                    data = null,
                    errorMessage = root.apiErrorMessageOrNull() ?: defaultApiErrorMessage(code),
                    rawBody = body
                )
            )
        }
        val dataElement = root["data"]
            ?: return JmxResult.Failure(JmxError.Schema("API 响应缺少 data 字段：${bodySampler.sample(body)}", field = "data"))
        val data = decodeDataElement(dataElement, tokenTimestampSeconds, body).unwrapOrReturn { return it }
        return JmxResult.Success(ApiEnvelope(code, data, null, body))
    }

    fun decodePlainEnvelope(body: String): JmxResult<ApiEnvelope> {
        val root = parseJsonObject(body).unwrapOrReturn { return it }
        val code = root.apiCodeOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("API 响应缺少 code 字段：${bodySampler.sample(body)}", field = "code"))
        return JmxResult.Success(
            ApiEnvelope(
                code = code,
                data = root["data"],
                errorMessage = root.apiErrorMessageOrNull(),
                rawBody = body
            )
        )
    }

    fun <T> fromJson(element: JsonElement, clazz: Class<T>): JmxResult<T> {
        return runCatching { gson.fromJson(element, clazz) }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Schema("数据模型解析失败：${clazz.simpleName}", cause = it)) }
        )
    }

    private fun parseJsonObject(body: String): JmxResult<JsonObject> {
        val element = parseJsonElement(body).unwrapOrReturn { return it }
        return if (element.isJsonObject) {
            JmxResult.Success(element.asJsonObject)
        } else {
            JmxResult.Failure(JmxError.Schema("响应不是 JSON Object：${bodySampler.sample(body)}"))
        }
    }

    private fun parseJsonElement(body: String): JmxResult<JsonElement> {
        return runCatching { JsonParser.parseString(body) }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Schema("JSON 解析失败：${bodySampler.sample(body)}", cause = it)) }
        )
    }

    private fun decodeDataElement(
        dataElement: JsonElement,
        tokenTimestampSeconds: Long,
        body: String
    ): JmxResult<JsonElement> {
        if (dataElement.isJsonNull) {
            return JmxResult.Failure(JmxError.Schema("API 响应 data 为 null：${bodySampler.sample(body)}", field = "data"))
        }
        if (dataElement.isJsonObject || dataElement.isJsonArray) {
            return JmxResult.Success(dataElement)
        }
        val encryptedData = dataElement.asStringOrNull()
            ?: return JmxResult.Failure(
                JmxError.Schema(
                    "API 响应 data 类型不支持：${dataElement.javaClass.simpleName}；${bodySampler.sample(body)}",
                    field = "data"
                )
            )
        if (encryptedData.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("API 响应加密 data 为空：${bodySampler.sample(body)}", field = "data"))
        }
        val key = JmxHash.md5Hex("$tokenTimestampSeconds${JmxProtocolConstants.DataSecret}")
        val decrypted = AesEcbPkcs7.decryptBase64ToString(encryptedData, key).unwrapOrReturn { return it }
        return parseJsonElement(decrypted)
    }

    private inline fun <T> JmxResult<T>.unwrapOrReturn(returnFailure: (JmxResult.Failure) -> Nothing): T {
        return when (this) {
            is JmxResult.Success -> value
            is JmxResult.Failure -> returnFailure(this)
        }
    }

    private fun JsonElement.asIntOrNull(): Int? = runCatching { asInt }.getOrNull()

    private fun JsonElement.asStringOrNull(): String? {
        return takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
    }

    private fun JsonObject.apiCodeOrNull(): Int? {
        return firstPrimitive("code", "status", "status_code")?.asIntOrNull()
    }

    private fun JsonObject.apiErrorMessageOrNull(): String? {
        return firstPrimitive("errorMsg", "error_msg", "msg", "message", "error")
            ?.asStringOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.firstPrimitive(vararg names: String): JsonElement? {
        return names.asSequence()
            .mapNotNull { this[it] }
            .firstOrNull { it.isJsonPrimitive }
    }

    private fun defaultApiErrorMessage(code: Int): String = "接口返回错误：$code"
}

class BodySampler(
    private val maxChars: Int = 160
) {
    fun sample(body: String): String {
        return body
            .replace(Regex("""(?i)(token|tokenparam|password|cookie|avs)["'=:\s]+[^,"'\s}]+""")) {
                "${it.groupValues[1]}=<redacted>"
            }
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .take(maxChars.coerceAtLeast(1))
    }
}
