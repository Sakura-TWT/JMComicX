package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiRoute

data class ApiRequest(
    val route: ApiRoute,
    val query: Map<String, String?> = emptyMap(),
    val form: Map<String, String?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val requireSuccessCode: Boolean = true
)

class ApiRequestBuilder(private val route: ApiRoute) {
    private val query = linkedMapOf<String, String?>()
    private val form = linkedMapOf<String, String?>()
    private val headers = linkedMapOf<String, String>()
    private var requireSuccessCode: Boolean = true

    fun query(name: String, value: String?): ApiRequestBuilder = apply {
        putOrRemove(query, name, value)
    }

    fun query(name: String, value: Int?): ApiRequestBuilder = query(name, value?.toString())

    fun query(name: String, value: Long?): ApiRequestBuilder = query(name, value?.toString())

    fun queryAtLeast(name: String, value: Int, minimum: Int): ApiRequestBuilder {
        return query(name, value.coerceAtLeast(minimum))
    }

    fun form(name: String, value: String?): ApiRequestBuilder = apply {
        putOrRemove(form, name, value)
    }

    fun header(name: String, value: String?): ApiRequestBuilder = apply {
        if (value == null) {
            headers.remove(name)
        } else {
            headers[name] = value
        }
    }

    fun requireSuccessCode(value: Boolean): ApiRequestBuilder = apply {
        requireSuccessCode = value
    }

    fun build(): ApiRequest {
        return ApiRequest(
            route = route,
            query = query.toMap(),
            form = form.toMap(),
            headers = headers.toMap(),
            requireSuccessCode = requireSuccessCode
        )
    }

    private fun putOrRemove(target: MutableMap<String, String?>, name: String, value: String?) {
        if (value == null) {
            target.remove(name)
        } else {
            target[name] = value
        }
    }
}

fun apiRequest(route: ApiRoute, configure: ApiRequestBuilder.() -> Unit = {}): ApiRequest {
    return ApiRequestBuilder(route).apply(configure).build()
}
