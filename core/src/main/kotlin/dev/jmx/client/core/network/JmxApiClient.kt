package dev.jmx.client.core.network

import com.google.gson.JsonElement
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class JmxApiClient(
    private val httpClient: JmxHttpClient,
    private val responseDecoder: ApiResponseDecoder = ApiResponseDecoder()
) {
    suspend fun requestJson(request: ApiRequest): JmxResult<JsonElement> {
        val raw = when (val result = httpClient.execute(request)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val envelope = when (
            val decoded = if (request.route.encryptedJson) {
                responseDecoder.decodeEncryptedEnvelope(raw.body, raw.tokenTimestampSeconds)
            } else {
                responseDecoder.decodePlainEnvelope(raw.body)
            }
        ) {
            is JmxResult.Success -> decoded.value
            is JmxResult.Failure -> return decoded
        }
        if (request.requireSuccessCode && envelope.code != 200) {
            return JmxResult.Failure(JmxError.Api(envelope.code, envelope.errorMessage ?: "接口返回错误：${envelope.code}"))
        }
        return envelope.data?.let { JmxResult.Success(it) }
            ?: JmxResult.Failure(JmxError.Schema("API 响应 data 为空", field = "data"))
    }

    suspend fun requestText(request: ApiRequest): JmxResult<String> {
        return when (val raw = httpClient.execute(request)) {
            is JmxResult.Success -> JmxResult.Success(raw.value.body)
            is JmxResult.Failure -> raw
        }
    }
}
