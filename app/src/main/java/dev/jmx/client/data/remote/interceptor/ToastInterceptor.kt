package dev.jmx.client.data.remote.interceptor

import dev.jmx.client.store.ToastManager
import dev.jmx.client.store.JmxDiagnostics
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class ToastInterceptor(
    private val toastManager: ToastManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestId = JmxDiagnostics.nextRequestId()
        val start = System.nanoTime()
        JmxDiagnostics.i(
            "Network",
            "Network request started",
            metadata = mapOf(
                "request_id" to requestId,
                "method" to request.method,
                "url" to request.url.redactForLog(),
                "content_type" to request.header("Content-Type").orEmpty(),
                "timeout_connect_ms" to chain.connectTimeoutMillis(),
                "timeout_read_ms" to chain.readTimeoutMillis(),
                "request_body_prefix" to request.bodyPreview()
            )
        )
        val response = try {
            chain.proceed(request)
        } catch (throwable: Throwable) {
            val costMs = (System.nanoTime() - start) / 1_000_000
            JmxDiagnostics.e(
                "Network",
                "Network request failed with exception",
                throwable,
                metadata = mapOf(
                    "request_id" to requestId,
                    "method" to request.method,
                    "url" to request.url.redactForLog(),
                    "cost_ms" to costMs,
                    "error_type" to throwable::class.java.simpleName
                )
            )
            throw throwable
        }
        val costMs = (System.nanoTime() - start) / 1_000_000

        if (!response.isSuccessful) {
            toastManager.showAsync("网络错误: ${response.code}")
            val metadata = mapOf(
                    "request_id" to requestId,
                    "status_code" to response.code,
                    "cost_ms" to costMs,
                    "url" to response.request.url.redactForLog(),
                    "response_size_bytes" to (response.body?.contentLength() ?: -1L),
                    "error_body_prefix" to response.bodyPreview()
                )
            if (response.code >= 500) {
                JmxDiagnostics.e(
                    "Network",
                    "Network request returned server error response",
                    metadata = metadata
                )
            } else {
                JmxDiagnostics.w(
                    "Network",
                    "Network request returned client or redirect non-success response",
                    metadata = metadata
                )
            }
        } else {
            JmxDiagnostics.i(
                "Network",
                "Network request succeeded",
                metadata = mapOf(
                    "request_id" to requestId,
                    "status_code" to response.code,
                    "cost_ms" to costMs,
                    "url" to response.request.url.redactForLog(),
                    "response_size_bytes" to (response.body?.contentLength() ?: -1L),
                    "response_body_prefix" to response.bodyPreview(),
                    "x_request_id" to response.header("X-Request-Id").orEmpty()
                )
            )
        }
        return response
    }

    private fun okhttp3.HttpUrl.redactForLog(): String {
        return newBuilder()
            .username("")
            .password("")
            .query(null)
            .build()
            .toString()
    }

    private fun Request.bodyPreview(): String {
        val body = body ?: return ""
        if (body.contentLength() > 2_048L) {
            return "[body too large: ${body.contentLength()} bytes]"
        }
        return runCatching {
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
                .take(100)
                .redactSecrets()
        }.getOrDefault("[unavailable]")
    }

    private fun Response.bodyPreview(): String {
        return runCatching {
            peekBody(2_048L)
                .string()
                .take(200)
                .redactSecrets()
        }.getOrDefault("[unavailable]")
    }

    private fun String.redactSecrets(): String {
        return replace(Regex("(?i)(password|token|authorization|cookie)=[^&\\s]+"), "$1=[REDACTED]")
    }
}
