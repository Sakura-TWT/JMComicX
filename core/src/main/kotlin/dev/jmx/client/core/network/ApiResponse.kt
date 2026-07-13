package dev.jmx.client.core.network

import com.google.gson.JsonElement
import dev.jmx.client.core.result.NetworkExchange

data class ApiEnvelope(
    val code: Int,
    val data: JsonElement?,
    val errorMessage: String?,
    val rawBody: String
)

data class RawNetworkResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String?,
    val requestUrl: String,
    val tokenTimestampSeconds: Long
)

data class TextNetworkResponse(
    val text: String,
    val exchange: NetworkExchange
)
