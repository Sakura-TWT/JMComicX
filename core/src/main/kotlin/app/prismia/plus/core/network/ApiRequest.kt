package app.prismia.plus.core.network

import app.prismia.plus.core.protocol.ApiRoute
import okio.ByteString.Companion.encodeUtf8

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

/** Stable identity of request semantics, without storing credentials in the key. */
fun ApiRequest.dedupKey(): String {
    val effectiveHeaders = linkedMapOf<String, String>()
    headers.forEach { (name, value) ->
        // JmxHttpClient delegates Accept-Encoding to OkHttp for transparent gzip.
        if (!name.equals("Accept-Encoding", ignoreCase = true)) effectiveHeaders[name.lowercase()] = value
    }
    val canonical = buildString {
        field("jm-request-v2")
        field(route.name)
        fields(query)
        fields(form)
        fields(effectiveHeaders)
        field(requireSuccessCode.toString())
        field(excludedEndpointUrl)
    }
    return canonical.encodeUtf8().sha256().hex()
}

private fun StringBuilder.field(value: String?) {
    if (value == null) append("-1:") else append(value.length).append(':').append(value)
}

private fun StringBuilder.fields(values: Map<String, String?>) {
    val entries = values.entries.filter { it.value != null }.sortedBy { it.key }
    field(entries.size.toString())
    entries.forEach { (key, value) -> field(key); field(value) }
}
