package dev.jmx.v2.foundation.network

import dev.jmx.v2.foundation.protocol.ApiRoute

data class ApiRequest(
    val route: ApiRoute,
    val query: Map<String, String?> = emptyMap(),
    val form: Map<String, String?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val requireSuccessCode: Boolean = true
)
