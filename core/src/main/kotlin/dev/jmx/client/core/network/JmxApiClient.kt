package dev.jmx.client.core.network

import com.google.gson.JsonElement
import dev.jmx.client.core.result.NetworkExchange
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class JmxApiClient(
    private val httpClient: JmxHttpClient,
    private val responseDecoder: ApiResponseDecoder = ApiResponseDecoder(),
    private val bodySampler: BodySampler = BodySampler()
) {
    suspend fun requestJson(request: ApiRequest): JmxResult<JsonElement> {
        val raw = when (val result = httpClient.execute(request)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val exchange = raw.toExchange(request)
        val envelope = when (
            val decoded = if (request.route.encryptedJson) {
                responseDecoder.decodeEncryptedEnvelope(raw.body, raw.tokenTimestampSeconds)
            } else {
                responseDecoder.decodePlainEnvelope(raw.body)
            }
        ) {
            is JmxResult.Success -> decoded.value
            is JmxResult.Failure -> return JmxResult.Failure(decoded.error.withExchange(exchange))
        }
        if (request.requireSuccessCode && envelope.code != 200) {
            return JmxResult.Failure(
                JmxError.Api(
                    code = envelope.code,
                    message = envelope.errorMessage ?: "接口返回错误：${envelope.code}",
                    exchange = exchange
                )
            )
        }
        return envelope.data?.let { JmxResult.Success(it) }
            ?: JmxResult.Failure(JmxError.Schema("API 响应 data 为空", field = "data", exchange = exchange))
    }

    suspend fun requestText(request: ApiRequest): JmxResult<String> {
        return when (val raw = requestTextResponse(request)) {
            is JmxResult.Success -> JmxResult.Success(raw.value.text)
            is JmxResult.Failure -> raw
        }
    }

    suspend fun requestTextResponse(request: ApiRequest): JmxResult<TextNetworkResponse> {
        return when (val raw = httpClient.execute(request)) {
            is JmxResult.Success -> JmxResult.Success(
                TextNetworkResponse(
                    text = raw.value.body,
                    exchange = raw.value.toExchange(request)
                )
            )
            is JmxResult.Failure -> raw
        }
    }

    private fun RawNetworkResponse.toExchange(request: ApiRequest): NetworkExchange {
        return NetworkExchange(
            route = request.route.path,
            requestUrl = requestUrl,
            statusCode = statusCode,
            contentType = contentType,
            tokenTimestampSeconds = tokenTimestampSeconds,
            bodySample = bodySampler.sample(body)
        )
    }
}

fun JmxError.withExchange(exchange: NetworkExchange): JmxError {
    return when (this) {
        is JmxError.Api -> copy(exchange = exchange)
        is JmxError.Decode -> copy(exchange = exchange)
        is JmxError.Http -> copy(exchange = exchange)
        is JmxError.Schema -> copy(exchange = exchange)
        is JmxError.Domain,
        is JmxError.Network,
        is JmxError.Unknown -> this
    }
}
