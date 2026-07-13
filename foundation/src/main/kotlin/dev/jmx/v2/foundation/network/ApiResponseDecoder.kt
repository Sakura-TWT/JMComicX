package dev.jmx.v2.foundation.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jmx.v2.foundation.crypto.AesEcbPkcs7
import dev.jmx.v2.foundation.crypto.JmxHash
import dev.jmx.v2.foundation.protocol.JmxProtocolConstants
import dev.jmx.v2.foundation.result.JmxError
import dev.jmx.v2.foundation.result.JmxResult

class ApiResponseDecoder(
    private val gson: Gson = Gson()
) {
    fun decodeEncryptedEnvelope(body: String, tokenTimestampSeconds: Long): JmxResult<ApiEnvelope> {
        val root = parseJsonObject(body).unwrapOrReturn { return it }
        val code = root["code"]?.asIntOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("API 响应缺少 code 字段", field = "code"))
        if (code != 200) {
            return JmxResult.Success(
                ApiEnvelope(
                    code = code,
                    data = null,
                    errorMessage = root["errorMsg"]?.asStringOrNull()
                        ?: root["msg"]?.asStringOrNull()
                        ?: "接口返回错误：$code",
                    rawBody = body
                )
            )
        }
        val encryptedData = root["data"]?.asStringOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("API 响应缺少加密 data 字段", field = "data"))
        val key = JmxHash.md5Hex("$tokenTimestampSeconds${JmxProtocolConstants.DataSecret}")
        val decrypted = AesEcbPkcs7.decryptBase64ToString(encryptedData, key).unwrapOrReturn { return it }
        val data = parseJsonElement(decrypted).unwrapOrReturn { return it }
        return JmxResult.Success(ApiEnvelope(code, data, null, body))
    }

    fun decodePlainEnvelope(body: String): JmxResult<ApiEnvelope> {
        val root = parseJsonObject(body).unwrapOrReturn { return it }
        val code = root["code"]?.asIntOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("API 响应缺少 code 字段", field = "code"))
        return JmxResult.Success(
            ApiEnvelope(
                code = code,
                data = root["data"],
                errorMessage = root["errorMsg"]?.asStringOrNull() ?: root["msg"]?.asStringOrNull(),
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
            JmxResult.Failure(JmxError.Schema("响应不是 JSON Object"))
        }
    }

    private fun parseJsonElement(body: String): JmxResult<JsonElement> {
        return runCatching { JsonParser.parseString(body) }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Schema("JSON 解析失败", cause = it)) }
        )
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
}
