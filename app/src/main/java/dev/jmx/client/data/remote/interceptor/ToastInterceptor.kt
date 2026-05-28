package dev.jmx.client.data.remote.interceptor

import dev.jmx.client.store.ToastManager
import dev.jmx.client.store.JmxDiagnostics
import okhttp3.Interceptor
import okhttp3.Response

class ToastInterceptor(
    private val toastManager: ToastManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.nanoTime()
        JmxDiagnostics.i(
            "Network",
            "request method=${request.method} url=${request.url.redactForLog()}"
        )
        val response = chain.proceed(chain.request())
        val costMs = (System.nanoTime() - start) / 1_000_000
        JmxDiagnostics.i(
            "Network",
            "response code=${response.code} cost=${costMs}ms url=${response.request.url.redactForLog()}"
        )

        if (!response.isSuccessful) {
            toastManager.showAsync("网络错误: ${response.code}")
            JmxDiagnostics.w(
                "Network",
                "non-success response code=${response.code} url=${response.request.url.redactForLog()}"
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
}
