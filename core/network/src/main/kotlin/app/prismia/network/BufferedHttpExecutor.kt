package app.prismia.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fully consumed response; neither an open socket nor credentials escape through toString. */
data class BufferedHttpResponse(val status: Int, val headers: Headers, val body: String, val finalUrl: HttpUrl) {
    override fun toString(): String = "BufferedHttpResponse(status=$status, payload=redacted)"
}

/**
 * Shared text transport mechanics. The caller retains ownership of cookies,
 * authentication, redirects and retry policy through its own OkHttp client.
 * Cancellation stays bound to the call until the entire response is consumed.
 */
class BufferedHttpExecutor(
    private val client: OkHttpClient,
    private val maxResponseBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(maxResponseBytes in 1..Int.MAX_VALUE.toLong())
    }

    suspend fun execute(request: Request): BufferedHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = response.body
                        if (body.contentLength() > maxResponseBytes) throw ResponseSizeLimitException(maxResponseBytes)
                        val buffer = Buffer()
                        val source = body.source()
                        while (true) {
                            val count = source.read(buffer, minOf(8192, maxResponseBytes - buffer.size + 1))
                            if (count == -1L) break
                            if (buffer.size > maxResponseBytes) throw ResponseSizeLimitException(maxResponseBytes)
                        }
                        // Keep OkHttp's charset/BOM behavior without another network read.
                        val text = buffer.asResponseBody(body.contentType(), buffer.size).use { it.string() }
                        BufferedHttpResponse(response.code, response.headers, text, response.request.url)
                    }
                    continuation.resume(result)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }
}

/** Not an IOException: retrying a response that exceeds a local limit cannot fix it. */
class ResponseSizeLimitException(val limitBytes: Long) : IllegalStateException("HTTP response exceeded the configured byte limit")
