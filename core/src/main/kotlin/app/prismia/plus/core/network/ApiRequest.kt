package app.prismia.plus.core.network

import app.prismia.plus.core.protocol.ApiRoute

data class ApiRequest(
    val route: ApiRoute,
    val query: Map<String, String?> = emptyMap(),
    val form: Map<String, String?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val requireSuccessCode: Boolean = true,
    val excludedEndpointUrl: String? = null
)

class ApiRequestBuilder(private val route: ApiRoute) {
    private val query = linkedMapOf<String, String?>()
    private val form = linkedMapOf<String, String?>()
    private val headers = linkedMapOf<String, String>()
    private var requireSuccessCode: Boolean = true
    private var excludedEndpointUrl: String? = null

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

    fun excludeEndpointUrl(value: String?): ApiRequestBuilder = apply {
        excludedEndpointUrl = value?.takeIf { it.isNotBlank() }
    }

    fun build(): ApiRequest {
        return ApiRequest(
            route = route,
            query = query.toMap(),
            form = form.toMap(),
            headers = headers.toMap(),
            requireSuccessCode = requireSuccessCode,
            excludedEndpointUrl = excludedEndpointUrl
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

/**
 * 请求去重键：同一方法+路径+参数的并发请求视为同一请求。
 * 不含 headers，避免登录态恢复重放时把不同会话的请求错误合并。
 */
fun ApiRequest.dedupKey(): String {
    val queryPart = query.entries
        .filter { it.value != null }
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value}" }
    val formPart = form.entries
        .filter { it.value != null }
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value}" }
    return "${route.method.name} ${route.path}?$queryPart#$formPart"
}
