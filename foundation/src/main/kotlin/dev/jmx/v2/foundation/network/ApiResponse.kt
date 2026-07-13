package dev.jmx.v2.foundation.network

import com.google.gson.JsonElement

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
